#!/usr/bin/env python3
"""Exercise the real macOS Network.framework TLS server using synthetic content only."""
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
import ssl
import subprocess
import tempfile
import time
import urllib.parse
import uuid
import base64

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "native/macos/.build/debug/CopyMoreHarness"


def check(condition, message):
    if not condition:
        raise AssertionError(message)


class Harness:
    def __init__(self, directory):
        self.directory = Path(directory)
        ticket_file = self.directory / "pairing-url"
        ticket_file.unlink(missing_ok=True)
        self.log = open(self.directory / "harness.log", "a")
        self.process = subprocess.Popen([str(BINARY), str(directory)], stdin=subprocess.PIPE, stdout=self.log, stderr=self.log, text=True)
        for _ in range(150):
            if ticket_file.exists():
                raw = urllib.parse.urlsplit(ticket_file.read_text()).query
                # Swift URLComponents uses RFC3986, not HTML form '+' encoding.
                self.ticket = {k: urllib.parse.unquote(v) for k, v in (item.split("=", 1) for item in raw.split("&"))}
                return
            if self.process.poll() is not None:
                break
            time.sleep(.1)
        self.close()
        raise RuntimeError("Server failed to start: " + (self.directory / "harness.log").read_text())

    def command(self, value):
        self.process.stdin.write(value + "\n")
        self.process.stdin.flush()

    def connect(self, fingerprint=None):
        # Pairing QR is the trust anchor. Never send credentials before verifying it.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = context.wrap_socket(socket.create_connection((self.ticket["host"], int(self.ticket["port"])), timeout=8), server_hostname="CopyMore")
        digest = hashlib.sha256(connection.getpeercert(binary_form=True)).hexdigest()
        if digest != (fingerprint or self.ticket["fp"]):
            connection.close()
            raise ssl.SSLError("Pinned certificate does not match")
        return connection

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            self.process.wait(timeout=5)
        self.log.close()


def send(connection, payload):
    connection.sendall((json.dumps(payload) + "\n").encode())


def read(stream):
    return json.loads(stream.readline())


def run():
    with tempfile.TemporaryDirectory(prefix="copymore-protocol-") as folder:
        harness = Harness(folder)
        try:
            device_id = str(uuid.uuid4())
            try:
                harness.connect("0" * 64)
                raise AssertionError("Wrong certificate was accepted")
            except ssl.SSLError:
                pass
            with harness.connect() as conn:
                send(conn, dict(type="pair", version=2, deviceId=device_id, name="Test Android", secret="wrong"))
                check(read(conn.makefile("rb"))["type"] == "error", "Bad pairing secret accepted")
            with harness.connect() as conn:
                stream = conn.makefile("rb")
                send(conn, dict(type="pair", version=2, deviceId=device_id, name="Test Android", secret=harness.ticket["key"]))
                response = read(stream)
                check(response["type"] == "paired", "Pairing failed")
                token, server_id, fingerprint = response["token"], response["serverId"], harness.ticket["fp"]
                check(len(base64.b64decode(token)) == 32, "Weak session token")
                check("push-v1" in response.get("features", []), "Phone sending capability missing")
                phone_id = str(uuid.uuid4())
                phone_text = "来自手机 📱 " + "x" * (32 * 1024)
                send(conn, dict(type="push_text", id=phone_id, text=phone_text))
                check(read(stream) == {"type": "push_received", "id": phone_id}, "Phone text not accepted")
                send(conn, dict(type="ping"))
                check(read(stream)["type"] == "pong", "Phone text echoed back automatically")
                harness.command("resend")
                check(read(stream)["text"] == phone_text, "Phone text not held by Mac")
                upload_id = str(uuid.uuid4())
                upload_block = os.urandom(48 * 1024)
                upload_hash = hashlib.sha256()
                upload_size = len(upload_block) * 683  # More than 32 MiB, no legacy 25 MiB cap.
                send(conn, dict(type="push_begin", id=upload_id, kind="file", name="../../phone.bin", mime="application/octet-stream", size=upload_size))
                check(read(stream)["type"] == "push_ready", "Phone upload not accepted")
                for _ in range(683):
                    upload_hash.update(upload_block)
                    send(conn, dict(type="push_chunk", id=upload_id, data=base64.b64encode(upload_block).decode()))
                send(conn, dict(type="push_end", id=upload_id, sha256=upload_hash.hexdigest()))
                check(read(stream)["type"] == "push_received", "Phone upload hash failed")
                harness.command("resend"); phone_offer = read(stream)
                check(phone_offer["name"] == "phone.bin" and phone_offer["size"] == upload_size, "Phone metadata/path handling failed")
                send(conn, dict(type="download", id=phone_offer["id"]))
                check(read(stream)["type"] == "transfer_start", "Could not read received phone file")
                phone_hash = hashlib.sha256()
                while True:
                    frame = read(stream)
                    if frame["type"] == "transfer_end": break
                    phone_hash.update(base64.b64decode(frame["data"]))
                check(phone_hash.hexdigest() == upload_hash.hexdigest() == frame["sha256"], "Phone file round trip corrupted")
                bad_id = str(uuid.uuid4())
                send(conn, dict(type="push_begin", id=bad_id, kind="file", name="bad.bin", mime="application/octet-stream", size=1))
                check(read(stream)["type"] == "push_ready", "Bad hash setup failed")
                send(conn, dict(type="push_chunk", id=bad_id, data=base64.b64encode(b"x").decode()))
                send(conn, dict(type="push_end", id=bad_id, sha256="0" * 64))
                check(read(stream)["type"] == "push_error", "Phone corrupt upload accepted")
                check(not list((Path(folder) / "received").glob("*/.part")), "Corrupt phone upload left partial file")
                harness.command("text:你好，Android！🔗")
                clip = read(stream)
                check(clip["type"] == "clipboard" and clip["text"] == "你好，Android！🔗", "Text corrupted")
                send(conn, dict(type="received", id=clip["id"]))
                conn.sendall(b'{"type":"pi')
                conn.sendall(b'ng"}\n{"type":"ping"}\n')
                check(read(stream)["type"] == "pong" and read(stream)["type"] == "pong", "Fragmented frames failed")
                # A sparse >2 GiB offer must not be loaded into memory or automatically sent.
                sparse = Path(folder) / "large-sparse.bin"
                with sparse.open("wb") as output:
                    output.truncate(3 * 1024 ** 3 + 37)
                harness.command("file:" + str(sparse))
                offer = read(stream)
                check(offer["type"] == "attachment_offer" and offer["size"] == 3 * 1024 ** 3 + 37, "64-bit size lost")
                send(conn, dict(type="ping"))
                check(read(stream)["type"] == "pong", "Attachment was pushed before download request")
                send(conn, dict(type="download", id=str(uuid.uuid4())))
                check(read(stream)["type"] == "download_error", "Unknown download ID accepted")
                file = Path(folder) / "fixture.bin"
                block = os.urandom(256 * 1024)
                expected = hashlib.sha256()
                with file.open("wb") as output:
                    for _ in range(512):
                        output.write(block); expected.update(block)
                harness.command("file:" + str(file))
                offer = read(stream)
                check(offer["type"] == "attachment_offer" and offer["size"] == 128 * 1024 ** 2, "Bad file offer")
                send(conn, dict(type="download", id=offer["id"]))
                check(read(stream)["type"] == "transfer_start", "Download did not start")
                check(read(stream)["type"] == "transfer_chunk", "No initial data")
                send(conn, dict(type="cancel_download", id=offer["id"]))
                while True:
                    item = read(stream)
                    if item["type"] == "download_cancelled": break
                    check(item["type"] == "transfer_chunk", "Unexpected cancellation frame")
                send(conn, dict(type="ping"))
                check(read(stream)["type"] == "pong", "Connection unusable after cancellation")
                send(conn, dict(type="download", id=offer["id"]))
                start = read(stream)
                check(start["type"] == "transfer_start", "Retry did not start")
                received_hash = hashlib.sha256(); received_size = 0
                while True:
                    item = read(stream)
                    check(item["id"] == start["id"], "Mixed transfer IDs")
                    if item["type"] == "transfer_end":
                        check(item["sha256"] == received_hash.hexdigest() == expected.hexdigest(), "File digest corrupted")
                        break
                    check(item["type"] == "transfer_chunk", "Unexpected transfer message")
                    data = base64.b64decode(item["data"])
                    check(len(data) <= 48 * 1024, "Unbounded transfer chunk")
                    received_hash.update(data); received_size += len(data)
                check(received_size == 128 * 1024 ** 2, "128 MiB transfer incomplete")
                send(conn, dict(type="received", id=start["id"]))
                with file.open("ab") as output: output.write(b"changed")
                send(conn, dict(type="download", id=offer["id"]))
                check(read(stream)["type"] == "download_error", "Changed source was silently accepted")
                # Image offers use the same explicit download flow, including empty-file support.
                image = Path(folder) / "fixture.png"
                image_data = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII=")
                image.write_bytes(image_data)
                harness.command("image:" + str(image))
                image_offer = read(stream)
                check(image_offer["kind"] == "image", "Image offer lost its type")
                send(conn, dict(type="download", id=image_offer["id"]))
                check(read(stream)["type"] == "transfer_start", "Image did not start")
                check(base64.b64decode(read(stream)["data"]) == image_data, "Image corrupted")
                check(read(stream)["sha256"] == hashlib.sha256(image_data).hexdigest(), "Image hash corrupted")
                empty = Path(folder) / "empty"; empty.touch()
                harness.command("file:" + str(empty)); empty_offer = read(stream)
                send(conn, dict(type="download", id=empty_offer["id"]))
                check(read(stream)["size"] == 0, "Empty file size lost")
                check(read(stream)["sha256"] == hashlib.sha256(b"").hexdigest(), "Empty file failed")
                harness.command("pause")
                time.sleep(.1)
                harness.command("text:paused-content")
                time.sleep(.1)
                send(conn, dict(type="ping"))
                check(read(stream)["type"] == "pong", "Paused sync sent content")
                harness.command("resume")
                check(read(stream)["text"] == "paused-content", "Resume failed")
            with harness.connect() as conn:
                send(conn, dict(type="pair", version=2, deviceId=str(uuid.uuid4()), secret=harness.ticket["key"]))
                check(read(conn.makefile("rb"))["type"] == "error", "Pairing ticket was reusable")
            # The same local vault backend is used by the packaged app and this harness.
            vault = Path(folder) / "identity-v1.json"
            check(stat.S_IMODE(Path(folder).stat().st_mode) == 0o700, "Identity directory is not private")
            check(stat.S_IMODE(vault.stat().st_mode) == 0o600, "Identity file is not private")
            saved_values = json.loads(vault.read_text())
            check(base64.b64decode(saved_values["server-id"]).decode() == server_id, "Server ID not persisted")
            check(len(json.loads(base64.b64decode(saved_values["devices"]))) == 1, "Pairing not persisted")
            harness.close()
            harness = Harness(folder)
            check(harness.ticket["fp"] == fingerprint and harness.ticket["id"] == server_id, "Identity changed on restart")
            with harness.connect() as conn:
                stream = conn.makefile("rb")
                send(conn, dict(type="auth", version=2, deviceId=device_id, token=token))
                check(read(stream)["type"] == "ready", "Saved pairing did not reconnect")
                harness.command("text:restart-ok")
                check(read(stream)["text"] == "restart-ok", "No content after reconnect")
                harness.command("revoke")
                check(stream.readline() == b"", "Revocation did not close active connection")
            with harness.connect() as conn:
                send(conn, dict(type="auth", version=2, deviceId=device_id, token=token))
                check(read(conn.makefile("rb"))["type"] == "error", "Revoked token was accepted")
            with harness.connect() as conn:
                conn.sendall(b"x" * (17 * 1024))
                check(read(conn.makefile("rb"))["type"] == "error", "Oversize frame accepted")
            harness.close()
            # A damaged file must fail closed, never silently replace an established identity.
            vault.write_text("corrupt-fixture")
            log = Path(folder) / "harness.log"
            log.write_text("")
            with log.open("a") as output:
                process = subprocess.Popen([str(BINARY), folder], stdin=subprocess.PIPE, stdout=output, stderr=output)
                try:
                    for _ in range(50):
                        if "本地配对文件无法读取" in log.read_text(): break
                        time.sleep(.1)
                    check("本地配对文件无法读取" in log.read_text(), "Damaged identity not reported")
                    check(vault.read_text() == "corrupt-fixture", "Damaged identity overwritten")
                finally:
                    process.terminate(); process.wait(timeout=5)
            print("PASS: phone text/no echo, >32 MiB phone upload round trip, upload path isolation/hash failure cleanup, private local vault permissions, damaged-file preservation, TLS pin, invalid secret, pairing, Unicode text, fragmented frames, 3 GiB metadata only, 128 MiB streamed download/hash, cancel/retry, changed-source rejection, image/empty download, pause/resume, single-use QR, persistent identity, authenticated reconnect, revocation, frame limit")
        finally:
            harness.close()


if __name__ == "__main__":
    run()
