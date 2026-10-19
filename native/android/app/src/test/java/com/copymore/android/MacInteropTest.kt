package com.copymore.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Optional integration test against the actual Swift/Network.framework executable. */
class MacInteropTest {
    @Test fun androidProtocolReceivesFromNativeMacOverPinnedTLS() {
        val binary = System.getenv("COPYMORE_HARNESS")
        assumeTrue("Set COPYMORE_HARNESS to the built Swift harness", binary != null && File(binary).canExecute())
        val dir = Files.createTempDirectory("copymore-interop").toFile()
        val log = File(dir, "log")
        val process = ProcessBuilder(binary!!, dir.path).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val ticketFile = File(dir, "pairing-url")
            val deadline = System.currentTimeMillis() + 15000
            while (!ticketFile.exists() && System.currentTimeMillis() < deadline && process.isAlive) Thread.sleep(100)
            assertTrue("Mac failed to start: ${log.readText()}", ticketFile.exists())
            val ticket = PairingTicket.parse(ticketFile.readText())
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(ticket.fingerprint)), null) }
            (context.socketFactory.createSocket(ticket.host, ticket.port) as SSLSocket).use { socket ->
                socket.soTimeout = 8000; socket.startHandshake()
                val input = BufferedInputStream(socket.inputStream)
                val hello = JSONObject().put("type", "pair").put("version", 2).put("deviceId", UUID.randomUUID().toString()).put("name", "Android JVM test").put("secret", ticket.secret)
                socket.outputStream.write((hello.toString() + "\n").toByteArray()); socket.outputStream.flush()
                assertEquals("paired", readFrame(input).getString("type"))
                val commands = process.outputStream.bufferedWriter()
                commands.write("text:电脑复制，Android 接收 🔗\n"); commands.flush()
                assertEquals("电脑复制，Android 接收 🔗", readFrame(input).getString("text"))
                val bytes = ByteArray(32 * 1024 * 1024) { (it % 251).toByte() }
                val fixture = File(dir, "fixture.bin").apply { writeBytes(bytes) }
                commands.write("file:${fixture.path}\n"); commands.flush()
                val offer = readFrame(input)
                assertEquals("attachment_offer", offer.getString("type"))
                val advertised = attachmentOffer(offer, ticket.id)
                socket.outputStream.write((JSONObject().put("type", "ping").toString() + "\n").toByteArray())
                assertEquals("pong", readFrame(input).getString("type"))
                socket.outputStream.write((JSONObject().put("type", "download").put("id", advertised.id).toString() + "\n").toByteArray())
                val start = readFrame(input)
                assertEquals("transfer_start", start.getString("type"))
                val receiveDir = File(dir, "received")
                IncomingTransfer(start, receiveDir, advertised).use { transfer ->
                    while (true) {
                        val frame = readFrame(input)
                        if (frame.getString("type") == "transfer_end") {
                            val clip = transfer.finish(frame)
                            assertArrayEquals(bytes, File(receiveDir, clip.fileName).readBytes()); break
                        }
                        transfer.append(frame)
                    }
                }
            }
            val wrongContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager("0".repeat(64))), null) }
            assertThrows(javax.net.ssl.SSLException::class.java) {
                (wrongContext.socketFactory.createSocket(ticket.host, ticket.port) as SSLSocket).use { it.soTimeout = 8000; it.startHandshake() }
            }
        } finally { process.destroy(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); if (process.isAlive) process.destroyForcibly(); dir.deleteRecursively() }
    }
}
