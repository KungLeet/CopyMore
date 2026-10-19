import Foundation
import Security
import CryptoKit

struct TLSIdentity {
    let identity: SecIdentity
    let fingerprint: String

    static func load(vault: Vault) throws -> TLSIdentity {
        let bundle: Data
        let password: String
        let saved = try vault.read("tls-identity"), pass = try vault.read("tls-password")
        if saved != nil || pass != nil {
            guard let saved, let pass, let text = String(data: pass, encoding: .utf8), !text.isEmpty else {
                throw AppError.message("本地身份文件不完整，已保留原文件；请检查文件后重新打开 CopyMore")
            }
            bundle = saved; password = text
        } else {
            guard try vault.read("server-id") == nil, try vault.read("devices") == nil else {
                throw AppError.message("本地证书缺失，已保留配对记录；请检查文件后重新打开 CopyMore")
            }
            password = try secureRandom().base64EncodedString()
            bundle = try generate(password: password)
            try vault.write(["tls-password": Data(password.utf8), "tls-identity": bundle])
        }
        var imported: CFArray?
        let options: [String: Any] = [kSecImportExportPassphrase as String: password,
                                      kSecImportToMemoryOnly as String: true]
        let status = SecPKCS12Import(bundle as CFData, options as CFDictionary, &imported)
        guard status == errSecSuccess, let entries = imported as? [[String: Any]], let first = entries.first,
              let raw = first[kSecImportItemIdentity as String] else { throw AppError.message("无法载入本机 TLS 身份（\(status)）") }
        let identity = raw as! SecIdentity
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess, let certificate else { throw AppError.message("找不到本机证书") }
        let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
        return TLSIdentity(identity: identity, fingerprint: digest.map { String(format: "%02x", $0) }.joined())
    }

    private static func generate(password: String) throws -> Data {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("copymore-identity-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: dir) }
        let key = dir.appendingPathComponent("key.pem"), cert = dir.appendingPathComponent("cert.pem"), p12 = dir.appendingPathComponent("identity.p12")
        try run(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "3650", "-subj", "/CN=CopyMore", "-keyout", key.path, "-out", cert.path])
        try run(["pkcs12", "-export", "-inkey", key.path, "-in", cert.path, "-out", p12.path, "-passout", "stdin"], input: password + "\n")
        return try Data(contentsOf: p12)
    }
    private static func run(_ args: [String], input: String? = nil) throws {
        let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/openssl"); p.arguments = args
        let stdin = Pipe(); p.standardInput = stdin
        p.standardOutput = FileHandle.nullDevice; p.standardError = FileHandle.nullDevice
        try p.run()
        if let input { stdin.fileHandleForWriting.write(Data(input.utf8)) }
        try? stdin.fileHandleForWriting.close()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw AppError.message("无法创建本机加密身份，请确认系统 OpenSSL 可用") }
    }
}
