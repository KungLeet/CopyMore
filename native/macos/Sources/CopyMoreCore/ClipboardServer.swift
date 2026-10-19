import AppKit
import Combine
import CryptoKit
import Network
import UniformTypeIdentifiers
import ImageIO

public final class ClipboardServer: ObservableObject {
    @Published public private(set) var status = "正在准备…"
    @Published public private(set) var devices = [TrustedDevice]()
    @Published public private(set) var connectedIDs = Set<String>()
    @Published public private(set) var latest: Clip?
    @Published public private(set) var pairingURL = ""
    @Published public private(set) var pairingExpires: Date?
    @Published public private(set) var lastDelivery = ""
    @Published public private(set) var error: String?
    @Published public var autoSync = true {
        didSet { if autoSync, let latest { broadcast(latest) } }
    }
    public private(set) var serverID = ""
    public let serverName = Host.current().localizedName ?? "我的 Mac"
    @Published public private(set) var latestSource = "本机"
    private let receivedDirectory: URL
    private var latestOriginDeviceID: String?
    private let vault: Vault
    private let monitorClipboard: Bool
    private var identity: TLSIdentity?
    private var listener: NWListener?
    private var peers = [UUID: Peer]()
    private var poller: Timer?
    private var changeCount = -1
    private var pairSecret = ""
    private var port: UInt16 = 0
    private var starting = false
    private var recentFailures = [Date]()
    private var fileGeneration = 0
    private var recentClips = [Clip]()

    public init(monitorClipboard: Bool = true, testDirectory: URL? = nil) {
        self.monitorClipboard = monitorClipboard
        self.vault = Vault(testDirectory: testDirectory)
        self.receivedDirectory = (testDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CopyMore")).appendingPathComponent("received", isDirectory: true)
    }

    public func start() {
        guard !starting, listener == nil else { return }
        starting = true; error = nil
        // Certificate generation may take a moment; never block the native UI.
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                let identity = try TLSIdentity.load(vault: vault)
                let id: String
                if let saved = try vault.read("server-id"), let value = String(data: saved, encoding: .utf8) { id = value }
                else { id = UUID().uuidString; try vault.write("server-id", Data(id.utf8)) }
                let saved = try vault.read("devices")
                let devices = try saved.map { try JSONDecoder().decode([TrustedDevice].self, from: $0) } ?? []
                DispatchQueue.main.async {
                    self.identity = identity; self.serverID = id; self.devices = devices
                    self.starting = false
                    do { try self.listen() } catch { self.fail(error) }
                }
            } catch { DispatchQueue.main.async { self.starting = false; self.fail(error) } }
        }
    }

    private func listen() throws {
        guard let identity, let securityIdentity = sec_identity_create(identity.identity) else { throw AppError.message("无法初始化 TLS") }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, securityIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        let tcp = NWProtocolTCP.Options(); tcp.enableKeepalive = true; tcp.keepaliveIdle = 20; tcp.keepaliveInterval = 10
        let parameters = NWParameters(tls: tls, tcp: tcp)
        let listener = try NWListener(using: parameters, on: .any)
        listener.service = NWListener.Service(name: "CopyMore-\(serverID)", type: "_copymore._tcp")
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = listener.port?.rawValue ?? 0; self.status = "等待手机连接"
                self.refreshPairing()
                self.poller?.invalidate()
                self.poller = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: true) { [weak self] _ in self?.tick() }
            case .failed(let error): self.listener?.cancel(); self.listener = nil; self.fail(error)
            case .waiting: self.status = "等待局域网权限或网络恢复"
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        self.listener = listener; listener.start(queue: .main)
    }

    public func refreshPairing() {
        do {
            pairSecret = try secureRandom().base64EncodedString()
            pairingExpires = Date().addingTimeInterval(600)
            updatePairingURL()
        } catch { fail(error) }
    }
    private func updatePairingURL() {
        guard let identity, port != 0, !pairSecret.isEmpty, let expires = pairingExpires, expires > Date() else { return }
        let addresses = monitorClipboard ? ipv4Addresses() : ["127.0.0.1"]
        guard let host = addresses.first else { pairingURL = ""; return }
        var url = URLComponents(); url.scheme = "copymore"; url.host = "pair"
        url.queryItems = ["v":"2", "id":serverID, "name":serverName, "host":host,
                          "port":String(port), "fp":identity.fingerprint, "key":pairSecret]
            .sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        let next = url.string ?? ""
        if pairingURL != next { pairingURL = next }
    }
    private func tick() {
        if let expires = pairingExpires, expires <= Date() { pairSecret = ""; pairingURL = "" }
        else { updatePairingURL() }
        guard monitorClipboard else { return }
        let board = NSPasteboard.general
        guard changeCount != board.changeCount else { return }
        changeCount = board.changeCount
        // Password managers mark confidential clipboard content; do not publish it.
        let excluded = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType", "org.nspasteboard.AutoGeneratedType"]
        if board.types?.contains(where: { excluded.contains($0.rawValue) }) == true { return }
        fileGeneration += 1; let generation = fileGeneration
        if let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly:true]) as? [URL], !urls.isEmpty {
            guard urls.count == 1, let url = urls.first else { error = "当前版本一次同步一个文件"; return }
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                do {
                    let type = UTType(filenameExtension: url.pathExtension)
                    let clip = try Clip(kind: type?.conforms(to: .image) == true ? "image" : "file", name: url.lastPathComponent, mime: type?.preferredMIMEType ?? "application/octet-stream", fileURL: url)
                    DispatchQueue.main.async { guard self?.fileGeneration == generation else { return }; self?.offer(clip) }
                } catch { DispatchQueue.main.async { if self?.fileGeneration == generation { self?.error = error.localizedDescription } } }
            }
        } else if let png = board.data(forType: .png) ?? board.data(forType: .tiff).flatMap({ NSBitmapImageRep(data: $0)?.representation(using: .png, properties: [:]) }) {
            // Clipboard screenshots arrive as bytes. Spool once, then stream from disk.
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("copymore-image-\(UUID().uuidString).png")
                do {
                    try png.write(to: url, options: .atomic)
                    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
                    let clip = try Clip(kind: "image", name: "图片.png", mime: "image/png", fileURL: url, temporary: true)
                    DispatchQueue.main.async { guard self?.fileGeneration == generation else { return }; self?.offer(clip) }
                } catch {
                    try? FileManager.default.removeItem(at: url)
                    DispatchQueue.main.async { if self?.fileGeneration == generation { self?.error = error.localizedDescription } }
                }
            }
        } else if let text = board.string(forType: .string), !text.isEmpty {
            let data = Data(text.utf8)
            guard data.count <= Limits.text else { error = "文字内容超过 256 KB，请保存为文件后发送"; return }
            offer(Clip(kind: "text", name: "文字", mime: "text/plain", data: data))
        }
    }
    public func offer(_ clip: Clip) {
        latestSource = "本机"; latestOriginDeviceID = nil
        latest = clip; recentClips.insert(clip, at: 0); recentClips = Array(recentClips.prefix(20))
        error = nil; lastDelivery = autoSync ? "等待接收" : "已暂停"
        if autoSync { broadcast(clip) }
    }
    private func broadcast(_ clip: Clip) { for peer in peers.values where peer.deviceID != nil { send(clip, to: peer) } }
    public func resend() { guard let latest else { return }; broadcast(latest) }
    public func revoke(_ id: String) {
        do {
            let next = devices.filter { $0.id != id }
            try vault.write("devices", JSONEncoder().encode(next)); devices = next
            for peer in peers.values.filter({ $0.deviceID == id }) { peer.connection.cancel() }
        } catch { fail(error) }
    }
    public func stop() { poller?.invalidate(); listener?.cancel(); listener = nil; for peer in peers.values { peer.connection.cancel() }; peers.removeAll() }
    private func fail(_ error: Error) { self.error = error.localizedDescription; status = "需要检查连接" }

    private func accept(_ connection: NWConnection) {
        guard peers.count < 8 else { connection.cancel(); return }
        let peer = Peer(connection); peers[peer.id] = peer
        connection.stateUpdateHandler = { [weak self, weak peer] state in
            guard let self, let peer else { return }
            switch state {
            case .ready: self.receive(peer)
            case .failed, .cancelled:
                peer.stream = nil; peer.downloadID = nil; peer.io.async { peer.incoming = nil }; self.peers.removeValue(forKey: peer.id); self.updateConnected()
            default: break
            }
        }
        connection.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak peer] in if peer?.deviceID == nil { peer?.connection.cancel() } }
    }
    private func updateConnected() {
        connectedIDs = Set(peers.values.compactMap(\.deviceID))
        status = connectedIDs.isEmpty ? (devices.isEmpty ? "等待手机连接" : "等待手机上线 · 已记住配对") : "已连接 · 同一 Wi-Fi"
    }
    private func receive(_ peer: Peer) {
        peer.connection.receive(minimumIncompleteLength: 1, maximumLength: Limits.incomingFrame) { [weak self, weak peer] data, _, complete, error in
            guard let self, let peer else { return }
            if let data {
                do {
                    for line in try peer.decoder.append(data, maximum: peer.deviceID == nil ? Limits.incomingFrame : 2 * 1024 * 1024) {
                        guard let object = try JSONSerialization.jsonObject(with: line) as? [String: Any] else { throw AppError.message("无效消息") }
                        try self.handle(object, peer)
                    }
                } catch { self.reject(peer, reason: "invalid_message"); return }
            }
            if complete || error != nil { peer.connection.cancel() } else { self.receive(peer) }
        }
    }
    private func handle(_ msg: [String: Any], _ peer: Peer) throws {
        let type = msg["type"] as? String ?? ""
        if peer.deviceID == nil {
            recentFailures.removeAll { Date().timeIntervalSince($0) > 60 }
            guard recentFailures.count < 12, let id = msg["deviceId"] as? String, UUID(uuidString: id) != nil,
                  (msg["version"] as? Int) == 2 else { reject(peer, reason: "invalid_handshake"); return }
            if type == "pair" {
                guard let secret = msg["secret"] as? String, !pairSecret.isEmpty, let expires = pairingExpires, expires > Date(), constantTimeEqual(secret, pairSecret) else { reject(peer, reason: "pairing_expired"); return }
                let token = try secureRandom().base64EncodedString()
                let device = TrustedDevice(id: id, name: String((msg["name"] as? String ?? "Android 手机").prefix(80)), token: token)
                let next = devices.filter { $0.id != id } + [device]
                try vault.write("devices", JSONEncoder().encode(next)); devices = next; peer.deviceID = id
                // A scanned ticket is single-use. Generate another explicitly for additional phones.
                pairSecret = ""; pairingURL = ""; pairingExpires = nil
                peer.write(["type":"paired", "token":token, "serverId":serverID, "name":serverName, "features":["push-v1", "thumbnail-v1"]])
            } else if type == "auth", let token = msg["token"] as? String, let trusted = devices.first(where: {$0.id == id}), constantTimeEqual(token, trusted.token) {
                peer.deviceID = id; peer.write(["type":"ready", "serverId":serverID, "name":serverName, "features":["push-v1", "thumbnail-v1"]])
            } else { reject(peer, reason: "not_paired"); return }
            for existing in peers.values where existing.id != peer.id && existing.deviceID == id { existing.connection.cancel() }
            updateConnected(); if autoSync, let latest, peer.deviceID != latestOriginDeviceID { send(latest, to: peer) }; return
        }
        switch type {
        case "ping": peer.write(["type":"pong"])
        case "push_text":
            guard autoSync, let id = msg["id"] as? String, UUID(uuidString: id) != nil,
                  let text = msg["text"] as? String, text.utf8.count <= Limits.text else {
                peer.write(["type":"push_error", "id":msg["id"] as? String ?? "", "reason":"Mac 已暂停同步或文字超过上限"]); return
            }
            let clip = Clip(kind: "text", name: "文字", mime: "text/plain", data: Data(text.utf8))
            applyIncoming(clip, peer: peer, requestID: id)
        case "push_begin", "push_chunk", "push_end", "push_cancel":
            guard autoSync else { peer.io.async { peer.incoming = nil }; peer.write(["type":"push_error", "id":msg["id"] as? String ?? "", "reason":"Mac 已暂停同步"]); return }
            acceptUpload(msg, peer: peer)
        case "received": if msg["id"] as? String == latest?.id { lastDelivery = "手机已接收" }
        case "download":
            guard let id = msg["id"] as? String else { return }
            guard peer.stream == nil && !peer.opening else { peer.write(["type":"download_error", "id":id, "reason":"请先完成或取消当前下载"]); return }
            guard let clip = recentClips.first(where: { $0.id == id }), clip.source != nil else {
                peer.write(["type":"download_error", "id":id, "reason":"文件记录已过期，请在 Mac 上重新复制"]); return
            }
            startDownload(clip, peer: peer)
        case "cancel_download":
            if let id = msg["id"] as? String {
                if peer.downloadID == id {
                    peer.downloadID = nil; peer.requestNonce = nil; peer.stream = nil; peer.opening = false
                    lastDelivery = "手机已取消下载"
                }
                peer.write(["type":"download_cancelled", "id":id])
            }
        case "transfer_error":
            peer.stream = nil; peer.downloadID = nil; peer.opening = false
            lastDelivery = "下载失败，请在手机重试"
        default: break
        }
    }
    private func acceptUpload(_ message: [String: Any], peer: Peer) {
        peer.io.async { [weak self, weak peer] in
            guard let self, let peer else { return }
            let id = message["id"] as? String ?? ""
            do {
                switch message["type"] as? String {
                case "push_begin":
                    guard peer.incoming == nil else { throw AppError.message("请先完成当前发送") }
                    peer.incoming = try IncomingUpload(message, root: self.receivedDirectory)
                    peer.write(["type":"push_ready", "id":id])
                case "push_chunk":
                    guard let incoming = peer.incoming else { throw AppError.message("尚未开始接收文件") }
                    try incoming.append(message)
                case "push_end":
                    guard let incoming = peer.incoming else { throw AppError.message("尚未开始接收文件") }
                    let clip = try incoming.finish(message); peer.incoming = nil
                    DispatchQueue.main.async { [weak self, weak peer] in
                        guard let self, let peer, self.peers[peer.id] != nil, self.autoSync else { return }
                        self.applyIncoming(clip, peer: peer, requestID: id)
                    }
                case "push_cancel": peer.incoming = nil
                default: break
                }
            } catch {
                peer.incoming = nil
                peer.write(["type":"push_error", "id":id, "reason":error.localizedDescription])
            }
        }
    }
    private func applyIncoming(_ clip: Clip, peer: Peer, requestID: String) {
        if monitorClipboard {
            let board = NSPasteboard.general
            board.clearContents()
            if clip.kind == "text" { board.setString(clip.text, forType: .string) }
            else if let source = clip.source {
                board.writeObjects([source.url as NSURL])
                // File URLs support Finder; image data supports editors and messaging apps.
                if clip.kind == "image", clip.size <= 32 * 1024 * 1024,
                   let imageSource = CGImageSourceCreateWithURL(source.url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
                   let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any],
                   let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
                   width > 0, height > 0, Int64(width) * Int64(height) <= 32_000_000,
                   let image = NSImage(contentsOf: source.url), let tiff = image.tiffRepresentation {
                    board.setData(tiff, forType: .tiff)
                }
            }
            changeCount = board.changeCount; fileGeneration += 1
        }
        latest = clip; latestSource = "手机"; latestOriginDeviceID = peer.deviceID
        recentClips.insert(clip, at: 0); recentClips = Array(recentClips.prefix(20))
        error = nil; lastDelivery = "已写入 Mac 剪贴板"
        peer.write(["type":"push_received", "id":requestID])
    }
    private func reject(_ peer: Peer, reason: String) {
        recentFailures.append(Date())
        peer.write(["type":"error", "reason":reason]) { peer.connection.cancel() }
    }
    private func send(_ clip: Clip, to peer: Peer) {
        guard peer.deviceID != nil else { return }
        if clip.kind == "text" {
            lastDelivery = "发送中…"
            peer.write(["type":"clipboard", "id":clip.id, "kind":"text", "text":clip.text, "created":clip.created.timeIntervalSince1970])
        } else {
            lastDelivery = clip.kind == "image" || clip.size <= 8 * 1024 * 1024 ? "正在同步到手机…" : "可在手机点击下载"
            var offer: [String: Any] = ["type":"attachment_offer", "id":clip.id, "kind":clip.kind, "name":clip.name, "mime":clip.mime, "size":clip.size]
            if let thumbnail = clip.thumbnail { offer["thumbnail"] = thumbnail.base64EncodedString() }
            peer.write(offer)
        }
    }
    private func startDownload(_ clip: Clip, peer: Peer) {
        let nonce = UUID()
        peer.opening = true; peer.downloadID = clip.id; peer.requestNonce = nonce
        peer.io.async { [weak self, weak peer] in
            do {
                guard let source = clip.source else { return }
                let stream = try source.open()
                DispatchQueue.main.async {
                    guard let self, let peer, self.peers[peer.id] != nil, peer.downloadID == clip.id, peer.requestNonce == nonce else { return }
                    peer.opening = false; peer.stream = stream; self.lastDelivery = "手机下载中…"
                    peer.write(["type":"transfer_start", "id":clip.id, "kind":clip.kind, "name":clip.name, "mime":clip.mime, "size":clip.size]) { [weak self, weak peer] in
                        guard let self, let peer else { return }; self.sendChunk(clip, stream: stream, peer: peer)
                    }
                }
            } catch {
                DispatchQueue.main.async { guard let self, let peer, peer.downloadID == clip.id, peer.requestNonce == nonce else { return }; self.downloadFailed(clip.id, peer: peer, reason: "源文件已改变、移动或无法读取，请在 Mac 上重新复制") }
            }
        }
    }
    private func sendChunk(_ clip: Clip, stream: AttachmentStream, peer: Peer) {
        guard peers[peer.id] != nil, peer.stream === stream else { return }
        peer.io.async { [weak self, weak peer] in
            do {
                let bytes = try stream.next()
                let digest = bytes == nil ? stream.digest() : ""
                DispatchQueue.main.async {
                    guard let self, let peer, self.peers[peer.id] != nil, peer.stream === stream else { return }
                    if let bytes {
                        peer.write(["type":"transfer_chunk", "id":clip.id, "data":bytes.base64EncodedString()]) { [weak self, weak peer] in
                            guard let self, let peer else { return }; self.sendChunk(clip, stream: stream, peer: peer)
                        }
                    } else {
                        peer.write(["type":"transfer_end", "id":clip.id, "sha256":digest])
                        peer.stream = nil; peer.downloadID = nil
                    }
                }
            } catch {
                DispatchQueue.main.async { guard let self, let peer, peer.stream === stream else { return }; self.downloadFailed(clip.id, peer: peer, reason: "源文件读取失败或已改变，请重新复制后下载") }
            }
        }
    }
    private func downloadFailed(_ id: String, peer: Peer, reason: String) {
        peer.stream = nil; peer.downloadID = nil; peer.opening = false; lastDelivery = "下载失败"
        peer.write(["type":"download_error", "id":id, "reason":reason])
    }
}

private final class Peer {
    let id = UUID(); let connection: NWConnection
    var deviceID: String?; var decoder = LineDecoder()
    let io = DispatchQueue(label: "CopyMore.file-stream")
    var incoming: IncomingUpload? // Only accessed on io queue.
    var stream: AttachmentStream?; var downloadID: String?; var requestNonce: UUID?; var opening = false
    init(_ connection: NWConnection) { self.connection = connection }
    func write(_ object: [String: Any], then: (() -> Void)? = nil) {
        guard var data = try? JSONSerialization.data(withJSONObject: object) else { connection.cancel(); return }
        data.append(10)
        connection.send(content: data, completion: .contentProcessed { [weak self] error in if error != nil { self?.connection.cancel() } else { then?() } })
    }
}
