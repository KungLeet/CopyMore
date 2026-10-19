import SwiftUI
import CopyMoreCore
import CoreImage.CIFilterBuiltins
import ServiceManagement

@main
struct CopyMoreApp: App {
    @NSApplicationDelegateAdaptor(CopyMoreDelegate.self) private var delegate
    private var server: ClipboardServer { delegate.server }
    var body: some Scene {
        MenuBarExtra("CopyMore", systemImage: "doc.on.clipboard") {
            MainPanel(server: server)
        }.menuBarExtraStyle(.window)
        WindowGroup("CopyMore", id: "main") {
            MainPanel(server: server).padding(6)
        }.windowResizability(.contentSize).defaultSize(width: 380, height: 460)
    }
}

final class CopyMoreDelegate: NSObject, NSApplicationDelegate {
    let server = ClipboardServer()
    func applicationDidFinishLaunching(_ notification: Notification) { server.start() }
    func applicationWillTerminate(_ notification: Notification) { server.stop() }
}

struct MainPanel: View {
    @ObservedObject var server: ClipboardServer
    @State private var showPair = false
    @State private var showSettings = false
    @State private var login = SMAppService.mainApp.status == .enabled
    @State private var settingError: String?
    private let blue = Color(red: 0.03, green: 0.46, blue: 0.96)
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("CopyMore").font(.system(size: 23, weight: .bold))
                Spacer()
                Button { showSettings.toggle() } label: { Image(systemName: "gearshape").font(.system(size: 18)) }.buttonStyle(.plain).help("设置")
            }.padding(.bottom, 24)
            if showSettings {
                Toggle("登录时启动", isOn: $login).onChange(of: login) { _, enabled in
                    do { if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } }
                    catch { settingError = "设置未完成：\(error.localizedDescription)"; login = SMAppService.mainApp.status == .enabled }
                }.toggleStyle(.switch)
                Text("电脑启动后，CopyMore 就准备好了。 ").font(.caption).foregroundStyle(.secondary).padding(.top, 7)
                Divider().padding(.vertical, 18)
                ForEach(server.devices) { device in
                    HStack { Image(systemName: "iphone"); Text(device.name); Spacer(); Button("取消配对", role: .destructive) { server.revoke(device.id) } }.font(.caption).padding(.vertical, 5)
                }
                if server.devices.isEmpty { Text("尚未配对手机").foregroundStyle(.secondary).font(.callout) }
                if let settingError { Text(settingError).font(.caption).foregroundStyle(.red).padding(.top, 10) }
                Spacer(minLength: 20)
                Button("退出 CopyMore") { server.stop(); NSApp.terminate(nil) }.buttonStyle(.bordered)
            } else {
                HStack(spacing: 14) {
                    Circle().fill(server.connectedIDs.isEmpty ? Color.gray.opacity(0.5) : Color.green).frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(server.devices.first?.name ?? "连接你的手机").font(.system(size: 16, weight: .semibold))
                        Text(server.status).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }.padding(.bottom, 20)
                Divider()
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("自动同步").font(.system(size: 16, weight: .medium))
                        Text("图片与小文件自动同步，大文件点击下载").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Toggle("自动同步", isOn: $server.autoSync).labelsHidden().toggleStyle(.switch).tint(blue)
                }.padding(.vertical, 20)
                Divider()
                Text("最新内容").font(.system(size: 12)).foregroundStyle(.secondary).padding(.top, 19).padding(.bottom, 10)
                VStack(alignment: .leading, spacing: 14) {
                    if let clip = server.latest {
                        if clip.kind == "text" {
                            Text(String(clip.text.prefix(300))).font(.system(size: 15)).lineLimit(4).textSelection(.enabled)
                        } else {
                            HStack(spacing: 10) {
                                if let source = clip.source {
                                    Image(nsImage: NSWorkspace.shared.icon(forFile: source.url.path)).resizable().scaledToFit().frame(width: 48, height: 48)
                                }
                                VStack(alignment: .leading, spacing: 5) { Text(clip.name).lineLimit(2); Text(ByteCountFormatter.string(fromByteCount: clip.size, countStyle: .file)).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                        if let thumbnail = clip.thumbnail, let preview = NSImage(data: thumbnail) {
                            Image(nsImage: preview).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 160).clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        HStack { Text("\(server.latestSource) · \(clip.created.formatted(date: .omitted, time: .shortened))"); Spacer(); Text(server.autoSync ? server.lastDelivery : "已暂停") }.font(.system(size: 11)).foregroundStyle(.secondary)
                    } else { Text("在 Mac 上复制文字、图片或文件，\n内容会自动出现在这里。").font(.system(size: 14)).foregroundStyle(.secondary).lineSpacing(5) }
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Color.primary.opacity(0.035)).cornerRadius(11)
                if let error = server.error { Text(error).font(.caption).foregroundStyle(.red).padding(.top, 10) }
                HStack {
                    Button(server.devices.isEmpty ? "连接手机" : "连接新设备") { server.refreshPairing(); showPair = true }.buttonStyle(.plain).foregroundStyle(blue)
                    Spacer()
                    if server.latest != nil, !server.connectedIDs.isEmpty { Button("重新发送") { server.resend() }.buttonStyle(.plain).foregroundStyle(.secondary).font(.caption) }
                }.padding(.top, 22)
            }
        }.padding(23).frame(width: 380).frame(minHeight: 390).background(Color(nsColor: .windowBackgroundColor))
            .sheet(isPresented: $showPair) { PairPanel(server: server, done: { showPair = false }) }
    }
}

struct PairPanel: View {
    @ObservedObject var server: ClipboardServer
    let done: () -> Void
    @State private var copied = false
    var body: some View {
        VStack(spacing: 15) {
            HStack { Text("连接 Android 手机").font(.title3.bold()); Spacer(); Button(action: done) { Image(systemName: "xmark") }.buttonStyle(.plain) }
            Text("两台设备连接同一个 Wi-Fi，\n用手机 CopyMore 扫描下方二维码。").font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).lineSpacing(4)
            if !server.pairingURL.isEmpty, let qr = qrImage(server.pairingURL) {
                Image(nsImage: qr).interpolation(.none).resizable().frame(width: 240, height: 240).padding(12).background(.white).cornerRadius(12)
                Text("只配对一次，之后自动连接").font(.callout.weight(.medium))
                Text("二维码 10 分钟内有效，请勿分享给他人。").font(.caption).foregroundStyle(.secondary)
                HStack { Button(copied ? "已复制" : "复制配对链接") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(server.pairingURL, forType: .string); NSPasteboard.general.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")); copied = true }; Button("刷新二维码") { server.refreshPairing(); copied = false } }.font(.caption)
            } else if server.pairingExpires == nil, !server.devices.isEmpty {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 70)).foregroundStyle(.green).padding(35)
                Text("配对成功").font(.title2.bold()); Text("下次会自动找到这台手机。 ").foregroundStyle(.secondary)
            } else {
                Text("二维码已失效或网络尚未就绪").padding(40)
                Button("重新生成") { server.refreshPairing() }
            }
            Button("完成", action: done).buttonStyle(.borderedProminent).padding(.top, 8)
        }.padding(25).frame(width: 360)
    }
    private func qrImage(_ value: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 5, y: 5)), let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}
