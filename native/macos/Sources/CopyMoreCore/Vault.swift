import Foundation
import Security

enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let s) = self { return s }; return nil }
}

/// App-owned local storage. Never reads, writes or changes the user's Keychain.
/// The directory is owner-only (0700), including during atomic replacement of the file.
final class Vault {
    private let directory: URL
    private var file: URL { directory.appendingPathComponent("identity-v1.json") }
    private let lock = NSLock()

    init(testDirectory: URL? = nil) {
        directory = testDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CopyMore", isDirectory: true)
    }

    func read(_ account: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return try load()[account]
    }

    func write(_ account: String, _ data: Data) throws {
        try write([account: data])
    }

    func write(_ updates: [String: Data]) throws {
        lock.lock(); defer { lock.unlock() }
        var values = try load()
        values.merge(updates) { _, new in new }
        try JSONEncoder().encode(values).write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    private func load() throws -> [String: Data] {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        // Do not follow substituted links or reuse a directory owned by another account.
        let attributes = try fm.attributesOfItem(atPath: directory.path)
        guard attributes[.type] as? FileAttributeType == .typeDirectory,
              (attributes[.ownerAccountID] as? NSNumber)?.uint32Value == getuid() else {
            throw AppError.message("身份文件夹权限异常，请检查 CopyMore 的本地数据目录")
        }
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        guard fm.fileExists(atPath: file.path) else { return [:] }
        let info = try fm.attributesOfItem(atPath: file.path)
        guard info[.type] as? FileAttributeType == .typeRegular,
              (info[.ownerAccountID] as? NSNumber)?.uint32Value == getuid() else {
            throw AppError.message("身份文件权限异常，请检查 CopyMore 的本地数据目录")
        }
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        do { return try JSONDecoder().decode([String: Data].self, from: Data(contentsOf: file)) }
        catch { throw AppError.message("本地配对文件无法读取，已保留原文件；请检查文件后重新打开 CopyMore") }
    }
}

func secureRandom(_ count: Int = 32) throws -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { throw AppError.message("无法生成安全随机数") }
    return Data(bytes)
}

func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
    let a = Array(lhs.utf8), b = Array(rhs.utf8)
    guard a.count == b.count else { return false }
    var result: UInt8 = 0
    for i in a.indices { result |= a[i] ^ b[i] }
    return result == 0
}
