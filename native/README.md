# 原生客户端开发预览版 0.4.0

macOS 使用 SwiftUI / AppKit / Network.framework，Android 使用 Kotlin / Jetpack Compose / 系统局域网发现。没有浏览器运行时。

## 使用

1. Mac：解压 `build/CopyMore-macOS.zip`，将 CopyMore.app 放到“应用程序”后打开。首次请求局域网访问时允许。开发包为本地签名，尚未公证；如系统拦截，可在“系统设置 → 隐私与安全性”确认打开本地开发包。
2. Android：安装 `build/CopyMore-Android.apk` 并打开。首次扫码时允许相机权限；允许通知以便查看后台连接状态。
3. 两端连接到可相互访问的同一局域网。在 Mac 点击“连接手机”，手机点击“扫码连接 Mac”。也可以复制 Mac 的配对链接，在手机中使用“配对链接”。
4. 在 Mac 复制文字。手机收到后尝试写入剪贴板，可切换到聊天应用或编辑器粘贴。复制图片或 8 MiB 以内文件时自动下载并写入剪贴板；更大的文件保留“下载到手机”，完成后自动复制。图片显示预览，文件显示系统 MIME 类型图标（Android 10+；更低版本回退图标）。仍提供手动复制、分享和“另存为”。
5. 后续保留配对、两端运行，即会自动重连。Mac 设置可开启登录时启动；Android 设置保持“后台常驻”开启，并按页面提示允许后台耗电和通知；vivo 还需在系统“电池 → 后台耗电管理 → CopyMore”选择“允许后台耗电”，不要保留“智能控制后台耗电”；这是独立于 Android 电池优化授权的另一项设置。应用设置新增电池设置入口，未授权时首页提供后台接收引导。若移除任务后仍被清理，可再允许自启动并锁定最近任务。退出页面或移除最近任务不会主动停止服务；系统强制停止后需要重新打开应用。

图片和文件没有固定的 25 MB 上限，采用 64 位文件大小和固定大小分块传输。下载先保存在应用内部，不自动写入系统公共下载文件夹；下载后点击“另存为”选择永久保存位置。应用保留最近 20 条记录，删除旧记录时清理对应内部下载文件；正在下载的记录会暂时保留。没有 100 MB 总量上限，下载前检查剩余空间，并保留 16 MiB 余量。

下载时 Mac 必须在线，原文件必须保持可读取且未被修改。Mac 当前保留本次运行内最近 20 条复制记录；更旧的记录或 Mac 重启前的下载卡片可能过期，届时重新复制即可。断开连接或取消会删除未完成的文件，重试从头下载，暂未支持断点续传。

当前使用带 push-v1 能力协商的协议 v2，两端需要同时更新到 0.4 才支持双向同步。Mac 0.3.1 不再读取旧钥匙串数据，因此首次升级需要重新扫码一次；之后身份和配对随本地文件保留。旧钥匙串记录不会被修改或删除。

两端可分别删除配对。若手机删除应用或配对，应同时在 Mac 删除对应设备，以撤销旧令牌。Mac 端取消配对会立即断开该手机并拒绝旧令牌。

手机复制文字、图片或单个文件后，回到 CopyMore 会自动发送到 Mac，Mac 自动写入系统剪贴板；也可以从其他应用使用系统分享发送给 CopyMore。Android 10+ 限制后台读取剪贴板，故不承诺在其他应用中复制后无需切换即可自动发送。图片和文件上传会先流式复制到手机私有临时文件，成功/失败后清理；取消配对会清理待发送项。不要将“可以接收文件”理解为所有手机 App 都支持文件粘贴。Wi-Fi 的访客隔离、防火墙、VPN 或系统强制停止可能导致连接失败，首页会显示等待重连；恢复网络后无需重新配对。

## 本地身份存储

Mac 身份与配对令牌保存在 `~/Library/Application Support/CopyMore/identity-v1.json`。目录权限为 `0700`，文件权限为 `0600`，采用原子替换写入；损坏时保留原文件并报告错误，不静默生成新身份。PKCS#12 身份包含密码保护，但密码同存在本地文件中，因此静态保护依赖 macOS 当前用户的文件访问权限，不宣称具备钥匙串的应用级隔离。不要分享、同步到公共目录或随日志上传该文件。

TLS 证书只导入进程内存，不读写登录钥匙串；此 API 从 macOS 15 开始提供，因此当前最低系统版本为 macOS 15。局域网传输继续使用 TLS、二维码证书指纹校验与设备令牌认证。未来 Windows 本地存储应采用当前用户专属 ACL，不能直接照搬 POSIX 权限数字。

Android 配对记录仍保存在应用私有目录，使用 Android Keystore 密钥加密；没有 macOS 钥匙串依赖。

## 构建 macOS

需要 macOS 15+、Swift 工具链和 macOS SDK。本次在 Intel Mac 上编译为 x86_64；在 Apple Silicon 上运行相同步骤会构建本机 arm64 版本，尚未在该架构运行验证。

```sh
./scripts/build-macos.sh
```

产物在 `build/CopyMore.app` 和 `build/CopyMore-macOS.zip`。当前使用 ad-hoc 本地签名。正式分发前需开发者签名、公证及真机验收。设置登录启动前应将应用放在固定位置。

## 构建 Android

需要 JDK 17、Android SDK 35 / Build Tools 35.0.0，设置 `JAVA_HOME`、`ANDROID_HOME`，或在 `native/android/local.properties` 中设置本机 `sdk.dir`。最低 Android 8.0 / API 26，当前 target SDK 35。

```sh
./scripts/build-android.sh
```

脚本运行 Gradle 构建和单元测试，生成 `build/CopyMore-Android.apk`。扫码使用 ZXing Embedded，界面、相机和接收服务均在本机原生运行。

## 验证

```sh
# 实际 Mac TLS 服务测试（只生成测试内容，不读取用户剪贴板）
cd native/macos
swift build --product CopyMoreHarness
cd ../..
python3 scripts/test-protocol.py

# Android 协议代码连接实际 Mac TLS 服务
cd native/android
COPYMORE_HARNESS="$PWD/../macos/.build/debug/CopyMoreHarness" \
  ./gradlew :app:testDebugUnitTest --rerun :app:lintDebug
```

仅安装 Apple Command Line Tools 的环境可能不带 XCTest。本次使用真实 TLS 服务测试和 Android/JVM 互通测试验证；`swift test` 单元测试需完整 Xcode 环境。

应用私有目录中的 `connection-events.log` 仅记录服务生命周期、断线原因类别和每分钟一次的心跳确认，不记录剪贴板内容、设备名称、地址、令牌或配对链接。文件超过 32 KiB 时仅保留最近 100 行，避免无限增长。

测试与发布缺口见 [VALIDATION.md](VALIDATION.md)。
