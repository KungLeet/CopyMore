import Foundation
import Combine
import CopyMoreCore

guard CommandLine.arguments.count == 2 else { fatalError("Pass a temporary test directory") }
let directory = URL(fileURLWithPath: CommandLine.arguments[1])
let server = ClipboardServer(monitorClipboard: false, testDirectory: directory)
var subscriptions = Set<AnyCancellable>()
server.$pairingURL.sink { url in
    guard !url.isEmpty else { return }
    try? Data(url.utf8).write(to: directory.appendingPathComponent("pairing-url"), options: .atomic)
    try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: directory.appendingPathComponent("pairing-url").path)
}.store(in: &subscriptions)
server.$error.sink { if let error = $0 { fputs("Harness error: \(error)\n", stderr) } }.store(in: &subscriptions)
server.start()
DispatchQueue.global().async {
    while let line = readLine() {
        DispatchQueue.main.async {
            if line == "rotate" { server.refreshPairing() }
            else if line == "revoke" { for d in server.devices { server.revoke(d.id) } }
            else if line == "resend" { server.resend() }
            else if line == "pause" { server.autoSync = false }
            else if line == "resume" { server.autoSync = true }
            else if line.hasPrefix("text:") { server.offer(Clip(kind: "text", name: "文字", mime: "text/plain", data: Data(line.dropFirst(5).utf8))) }
            else if line.hasPrefix("file:"), let clip = try? Clip(kind: "file", name: "fixture.bin", mime: "application/octet-stream", fileURL: URL(fileURLWithPath: String(line.dropFirst(5)))) { server.offer(clip) }
            else if line.hasPrefix("image:"), let clip = try? Clip(kind: "image", name: "fixture.png", mime: "image/png", fileURL: URL(fileURLWithPath: String(line.dropFirst(6)))) { server.offer(clip) }
        }
    }
}
RunLoop.main.run()
