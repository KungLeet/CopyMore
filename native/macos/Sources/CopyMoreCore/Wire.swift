import Foundation
import CryptoKit
import ImageIO
import UniformTypeIdentifiers

public enum Limits {
    public static let text = 256 * 1024
    public static let incomingFrame = 16 * 1024
    public static let chunk = 48 * 1024
}

/// Only text is held in memory. Attachments retain a file reference and immutable metadata.
public struct Clip: Identifiable {
    public let id: String
    public let kind: String
    public let name: String
    public let mime: String
    public let data: Data
    public let created: Date
    public let source: AttachmentSource?
    public let thumbnail: Data?
    public var size: Int64 { source?.size ?? Int64(data.count) }
    public var text: String { String(data: data, encoding: .utf8) ?? "" }
    public init(kind: String, name: String, mime: String, data: Data) {
        precondition(kind == "text")
        self.id = UUID().uuidString; self.kind = kind; self.name = name; self.mime = mime
        self.data = data; self.created = Date(); self.source = nil; self.thumbnail = nil
    }
    public init(kind: String, name: String, mime: String, fileURL: URL, temporary: Bool = false, temporaryDirectory: URL? = nil) throws {
        self.id = UUID().uuidString; self.kind = kind; self.name = name; self.mime = mime
        self.data = Data(); self.created = Date(); self.source = try AttachmentSource(url: fileURL, temporary: temporary, temporaryDirectory: temporaryDirectory)
        self.thumbnail = kind == "image" ? Self.makeThumbnail(fileURL) : nil
    }
    private static func makeThumbnail(_ url: URL) -> Data? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceThumbnailMaxPixelSize: 480, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.65] as CFDictionary)
        guard CGImageDestinationFinalize(destination), data.length <= 96 * 1024 else { return nil }
        return data as Data
    }

}

public final class AttachmentSource {
    public let url: URL
    public var size: Int64 { stamp.size }
    private let stamp: FileStamp
    private let temporary: Bool
    private let temporaryDirectory: URL?
    init(url: URL, temporary: Bool, temporaryDirectory: URL?) throws {
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
        self.stamp = try FileStamp(file); self.url = url; self.temporary = temporary; self.temporaryDirectory = temporaryDirectory
    }
    func open() throws -> AttachmentStream {
        let file = try FileHandle(forReadingFrom: url)
        do {
            guard try FileStamp(file) == stamp else { throw AppError.message("源文件已改变，请在 Mac 上重新复制") }
            return AttachmentStream(file: file, stamp: stamp)
        } catch { try? file.close(); throw error }
    }
    deinit { if temporary { try? FileManager.default.removeItem(at: temporaryDirectory ?? url) } }
}

private struct FileStamp: Equatable {
    let size: Int64, inode: UInt64, device: Int32, modified: Int, modifiedNS: Int, changed: Int, changedNS: Int
    init(_ file: FileHandle) throws {
        var info = stat()
        guard fstat(file.fileDescriptor, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size >= 0 else { throw AppError.message("请复制一个可读取的普通文件") }
        size = info.st_size; inode = info.st_ino; device = info.st_dev
        modified = info.st_mtimespec.tv_sec; modifiedNS = info.st_mtimespec.tv_nsec
        changed = info.st_ctimespec.tv_sec; changedNS = info.st_ctimespec.tv_nsec
    }
}

final class AttachmentStream {
    private let file: FileHandle
    private let stamp: FileStamp
    private var hash = SHA256()
    private var sent: Int64 = 0
    fileprivate init(file: FileHandle, stamp: FileStamp) { self.file = file; self.stamp = stamp }
    func next() throws -> Data? {
        if sent == stamp.size {
            guard try FileStamp(file) == stamp else { throw AppError.message("源文件在下载过程中改变，请重新复制") }
            return nil
        }
        let bytes = try file.read(upToCount: Int(min(Int64(Limits.chunk), stamp.size - sent))) ?? Data()
        guard !bytes.isEmpty else { throw AppError.message("源文件已改变或无法继续读取") }
        hash.update(data: bytes); sent += Int64(bytes.count); return bytes
    }
    func digest() -> String { hash.finalize().map { String(format: "%02x", $0) }.joined() }
    deinit { try? file.close() }
}

public struct LineDecoder {
    private var buffer = Data()
    public init() {}
    public mutating func append(_ data: Data, maximum: Int = Limits.incomingFrame) throws -> [Data] {
        buffer.append(data)
        var lines = [Data]()
        while let index = buffer.firstIndex(of: 10) {
            guard buffer.distance(from: buffer.startIndex, to: index) <= maximum else { throw AppError.message("消息过长") }
            lines.append(Data(buffer[..<index])); buffer.removeSubrange(...index)
        }
        guard buffer.count <= maximum else { throw AppError.message("消息过长") }
        return lines
    }
}

public struct TrustedDevice: Codable, Identifiable {
    public let id: String
    public let name: String
    let token: String
}

public func ipv4Addresses() -> [String] {
    var interfaces: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&interfaces) == 0, let first = interfaces else { return [] }
    defer { freeifaddrs(interfaces) }
    var result = [String](); var pointer: UnsafeMutablePointer<ifaddrs>? = first
    while let current = pointer {
        defer { pointer = current.pointee.ifa_next }
        let entry = current.pointee
        guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET), entry.ifa_flags & UInt32(IFF_LOOPBACK) == 0,
              entry.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 { result.append(String(cString: host)) }
    }
    return result
}
