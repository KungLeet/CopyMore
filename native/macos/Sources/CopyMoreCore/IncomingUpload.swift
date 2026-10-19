import Foundation
import CryptoKit

/// One authenticated upload per peer. Bounded chunks, private paths, hash verification.
final class IncomingUpload {
    let id: String
    let size: Int64
    private let kind: String, name: String, mime: String
    private let directory: URL, partial: URL, destination: URL
    private let output: FileHandle
    private var count: Int64 = 0
    private var hash = SHA256()
    private var finished = false

    init(_ header: [String: Any], root: URL) throws {
        guard let id = header["id"] as? String, UUID(uuidString: id) != nil,
              let kind = header["kind"] as? String, ["image", "file"].contains(kind),
              let number = header["size"] as? NSNumber, number.int64Value >= 0,
              number.doubleValue == Double(number.int64Value) else { throw AppError.message("文件信息无效") }
        self.id = id; self.kind = kind; self.size = number.int64Value
        let raw = (header["name"] as? String ?? "文件").replacingOccurrences(of: "\\", with: "/")
        let clean = String((raw as NSString).lastPathComponent.filter { !$0.isNewline && !$0.isASCIIControl }.prefix(160))
        self.name = clean.isEmpty || clean == "." || clean == ".." ? "文件" : clean
        self.mime = String((header["mime"] as? String ?? "application/octet-stream").prefix(100))
        let fm = FileManager.default
        try fm.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let free = (try fm.attributesOfFileSystem(forPath: root.path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        guard size <= max(0, free - 16 * 1024 * 1024) else { throw AppError.message("Mac 存储空间不足") }
        directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        partial = directory.appendingPathComponent(".part")
        destination = directory.appendingPathComponent(name == ".part" ? "文件.part" : name)
        try fm.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        guard fm.createFile(atPath: partial.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            try? fm.removeItem(at: directory); throw AppError.message("无法创建接收文件")
        }
        do { output = try FileHandle(forWritingTo: partial) }
        catch { try? fm.removeItem(at: directory); throw error }
    }
    func append(_ message: [String: Any]) throws {
        guard message["id"] as? String == id, let encoded = message["data"] as? String,
              encoded.utf8.count <= 65536, let bytes = Data(base64Encoded: encoded),
              Int64(bytes.count) <= size - count else { throw AppError.message("文件分块无效") }
        try output.write(contentsOf: bytes); hash.update(data: bytes); count += Int64(bytes.count)
    }
    func finish(_ message: [String: Any]) throws -> Clip {
        let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard message["id"] as? String == id, count == size, message["sha256"] as? String == digest else {
            throw AppError.message("文件传输不完整或校验失败")
        }
        try output.synchronize(); try output.close()
        try FileManager.default.moveItem(at: partial, to: destination)
        let clip = try Clip(kind: kind, name: destination.lastPathComponent, mime: mime, fileURL: destination, temporary: true, temporaryDirectory: directory)
        finished = true; return clip
    }
    deinit { try? output.close(); if !finished { try? FileManager.default.removeItem(at: directory) } }
}

private extension Character {
    var isASCIIControl: Bool { unicodeScalars.contains { $0.value < 32 || $0.value == 127 } }
}
