package com.copymore.android

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private var savingId: String? = null
    private var hasClipboardFocus = false
    private var skipNextClipboardRead = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (hasClipboardFocus && ::repo.isInitialized) repo.sender.captureClipboard()
    }
    private var backgroundStatus by mutableStateOf(BackgroundStatus())
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { pair(it) } }
    private val saveFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val clip = repo.state.value.clips.firstOrNull { it.id == savingId }
        if (result.resultCode == RESULT_OK && destination != null && clip != null) {
            val app = applicationContext
            Executors.newSingleThreadExecutor().also { executor -> executor.execute {
                val success = runCatching {
                    app.contentResolver.openOutputStream(destination)?.use { output -> File(repo.directory, clip.fileName).inputStream().use { it.copyTo(output) } } ?: error("无法打开保存位置")
                }.isSuccess
                runOnUiThread { Toast.makeText(app, if (success) "已保存" else "保存失败，请重试", Toast.LENGTH_SHORT).show() }
                executor.shutdown()
            } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); repo = Repository.get(this); savingId = savedInstanceState?.getString("savingId")
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
        if (repo.state.value.server != null && repo.store.enabled) startConnection()
        receiveShare(intent)
        setContent {
            val state by repo.state.collectAsState()
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Paper, surface = Color.White, onSurface = Ink, onBackground = Ink, secondary = Blue)) {
                Surface(Modifier.fillMaxSize(), color = Paper) {
                    CopyMoreScreen(state, scan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 Mac 上的 CopyMore 二维码").setBeepEnabled(false).setOrientationLocked(false)) },
                        pair = ::pair, sendClipboard = { repo.sender.captureClipboard(explicit = true) }, copy = repo::copy, share = ::share, save = ::save, download = repo::requestDownload, cancelDownload = repo::cancelDownload,
                        background = backgroundStatus, batterySettings = { BackgroundSupport.battery(this) },
                        notificationSettings = { BackgroundSupport.notifications(this) }, vendorBatterySettings = { BackgroundSupport.vendorBattery(this) }, appSettings = { BackgroundSupport.appDetails(this) },
                        enabled = { on ->
                            repo.store.enabled = on; repo.state.update { it.copy(enabled = on, status = if (on) "正在寻找你的 Mac…" else "后台常驻已关闭") }
                            if (on) startConnection() else stopService(Intent(this, SyncService::class.java))
                        }, forget = { repo.forget(); stopService(Intent(this, SyncService::class.java)) }, clear = repo::clearHistory)
                }
            }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); hasClipboardFocus = hasFocus
        if (hasFocus && ::repo.isInitialized) {
            if (skipNextClipboardRead) skipNextClipboardRead = false else repo.sender.captureClipboard()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); receiveShare(intent)
    }
    private fun receiveShare(incoming: Intent) {
        if (incoming.action != Intent.ACTION_SEND) return
        skipNextClipboardRead = !hasClipboardFocus
        repo.sender.share(incoming)
        // Consume the one-shot share so recreation/returning later cannot resend it.
        incoming.action = Intent.ACTION_MAIN
        incoming.removeExtra(Intent.EXTRA_STREAM); incoming.removeExtra(Intent.EXTRA_TEXT); incoming.clipData = null
    }
    override fun onDestroy() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume(); backgroundStatus = BackgroundSupport.read(this)
        if (::repo.isInitialized) repo.event("应用界面进入前台")
    }
    override fun onStop() {
        super.onStop()
        if (::repo.isInitialized) repo.event("应用界面进入后台；连接由独立服务保持")
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("savingId", savingId); super.onSaveInstanceState(outState) }
    private fun startConnection(reconnect: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching { SyncService.start(this, reconnect) }.onFailure { repo.status("未能启动自动连接", error = "请重新打开应用后重试。") }
    }
    private fun pair(link: String) {
        runCatching { PairingTicket.parse(link) }.onSuccess { ticket ->
            repo.pendingTicket = ticket; repo.store.enabled = true
            repo.state.update { it.copy(enabled = true, error = null, status = "正在配对…") }; startConnection(true)
        }.onFailure { repo.state.update { it.copy(error = "二维码或链接无效，请在 Mac 上重新生成。") } }
    }
    private fun share(clip: ReceivedClip) {
        val intent = Intent(Intent.ACTION_SEND).setType(clip.mime)
        if (clip.kind == "text") intent.putExtra(Intent.EXTRA_TEXT, clip.text)
        else {
            val uri = repo.uri(clip); intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri(clip.name, uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "使用其他应用打开")) }.onFailure { Toast.makeText(this, "没有可用的应用", Toast.LENGTH_SHORT).show() }
    }
    private fun save(clip: ReceivedClip) {
        savingId = clip.id
        saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(clip.mime).putExtra(Intent.EXTRA_TITLE, clip.name))
    }
}

private val Blue = Color(0xFF1479F8)
private val Paper = Color(0xFFFAFBFE)
private val Ink = Color(0xFF192132)
private val Muted = Color(0xFF7B8494)

@Composable private fun CopyMoreScreen(state: AppState, scan: () -> Unit, pair: (String) -> Unit, sendClipboard: () -> Unit, copy: (ReceivedClip) -> Unit, share: (ReceivedClip) -> Unit, save: (ReceivedClip) -> Unit, download: (ReceivedClip) -> Unit, cancelDownload: () -> Unit, background: BackgroundStatus, batterySettings: () -> Unit, notificationSettings: () -> Unit, vendorBatterySettings: () -> Unit, appSettings: () -> Unit, enabled: (Boolean) -> Unit, forget: () -> Unit, clear: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (history) IconButton(onClick = { history = false }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Text(if (history) "最近接收" else "CopyMore", fontSize = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "设置", tint = Muted) }
            }
        }
        if (!history) {
            item {
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).background(Blue.copy(alpha = .07f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.LaptopMac, null, tint = Blue) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(state.server?.name ?: "连接你的 Mac", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (state.connected) Color(0xFF32B578) else Muted, RoundedCornerShape(50)))
                            Text(state.status, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            if (state.server == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Devices, null, Modifier.size(84.dp), tint = Blue)
                        Spacer(Modifier.height(26.dp))
                        Text("电脑复制，手机就有", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("连接同一个 Wi-Fi，扫描一次二维码。\n以后靠近，就会自动连接。", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                        Spacer(Modifier.height(30.dp))
                        Button(onClick = scan, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(9.dp)); Text("扫码连接 Mac", fontSize = 16.sp)
                        }
                        TextButton(onClick = { showLink = true }) { Text("使用配对链接", color = Muted) }
                    }
                }
            } else {
                if (state.enabled && !background.batteryExempt) item {
                    Column(Modifier.fillMaxWidth().background(Blue.copy(alpha = .06f), RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("让后台也能接收", fontWeight = FontWeight.SemiBold)
                        Text("需要允许系统后台运行，切到其他应用后才能继续接收。后台接收可能增加耗电。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        Button(onClick = batterySettings) { Text("允许后台运行") }
                        if (background.vivo) {
                            Text("vivo 还需在电池设置中，找到 CopyMore 并选择「允许后台耗电」。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                            TextButton(onClick = vendorBatterySettings) { Text("打开 vivo 电池设置") }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最新内容", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { history = true }) { Text("最近接收", fontSize = 12.sp, color = Muted); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Muted) }
                    }
                }
                item {
                    state.clips.firstOrNull()?.let { ClipCard(it, state, copy, share, save, download, cancelDownload) } ?: Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(25.dp)) {
                        Icon(Icons.Outlined.ContentPaste, null, Modifier.size(36.dp), tint = Blue)
                        Spacer(Modifier.height(20.dp)); Text("准备好了", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp)); Text("在 Mac 上复制文字、图片或文件，\n收到的内容会出现在这里。", color = Muted, lineHeight = 23.sp, fontSize = 14.sp)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = sendClipboard, enabled = state.canSend && !state.sending) { Text("发送手机剪贴板到 Mac") }
                        state.sendStatus?.let { Text(it, color = Blue, fontSize = 12.sp) }
                        Text("手机复制后回到这里会自动发送，也可在其他应用中分享给 CopyMore。Android 限制后台读取其他应用的剪贴板。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                item { Text("文字、图片和 8 MB 以内文件自动接收并复制；大文件点击下载后自动复制。目标应用需支持图片或文件粘贴，也可使用分享。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 6.dp)) }
            }
        } else {
            if (state.clips.isEmpty()) item { Text("还没有收到内容", color = Muted) }
            items(state.clips, key = { it.id }) { clip -> ClipCard(clip, state, copy, share, save, download, cancelDownload) }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
    }
    if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text("使用配对链接") }, text = { Column {
        Text("在 Mac 的连接窗口复制链接，然后粘贴到这里。", fontSize = 14.sp, color = Muted)
        Spacer(Modifier.height(14.dp)); OutlinedTextField(link, { link = it }, label = { Text("配对链接") }, maxLines = 3)
    } }, confirmButton = { TextButton(onClick = { pair(link); link = ""; showLink = false }, enabled = link.isNotBlank()) { Text("连接") } }, dismissButton = { TextButton(onClick = { showLink = false; link = "" }) { Text("取消") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("设置") }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("后台常驻", Modifier.weight(1f)); Switch(state.enabled, enabled, enabled = state.server != null) }
        Text("开启后由独立服务接收，并在开机后尝试自动连接。还需允许系统后台运行；服务运行中并不代表系统已放行。关闭此项会停止连接。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Text(if (state.serviceRunning) "连接服务正在运行" else "连接服务未运行", fontSize = 12.sp, color = if (state.serviceRunning) Blue else Muted)
        TextButton(onClick = batterySettings, enabled = !background.batteryExempt) {
            Text(if (background.batteryExempt) "✓ 已允许不受电池优化限制" else "允许后台运行（电池优化）")
        }
        TextButton(onClick = notificationSettings) {
            Text(if (background.notificationsEnabled) "✓ 连接通知已开启" else "开启连接通知")
        }
        if (background.backgroundRestricted) Text("系统当前限制后台运行，请在应用设置中解除限制。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        if (background.vivo) {
            Text("vivo：在电池 → 后台耗电管理中找到 CopyMore，选择「允许后台耗电」，不要选「智能控制后台耗电」。这与上面的 Android 电池优化授权是两项设置。若一键清理后断开，可再允许自启动并锁定最近任务。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            TextButton(onClick = vendorBatterySettings) { Text("打开 vivo 电池设置") }
        }
        TextButton(onClick = appSettings) { Text("打开系统应用设置") }
        Text("后台接收可能增加耗电。系统“强行停止”或主动停止服务后，需要重新打开应用。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        state.lastDisconnect?.let { Text("上次中断：$it", fontSize = 11.sp, color = Muted) }
        HorizontalDivider()
        TextButton(onClick = { settings = false; scan() }) { Text(if (state.server == null) "连接 Mac" else "重新扫码配对") }
        if (state.server != null) TextButton(onClick = { confirmForget = true }) { Text("取消配对", color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { confirmClear = true }) { Text("清空接收记录", color = Muted) }
        Text("本机保留最近 20 条。下载保存在应用内，另存为可长期保留。\nCopyMore 0.4 · 原生开发预览版", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
    } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text("取消与 Mac 的配对？") }, text = { Text("下次连接需要重新扫码。Mac 端也可以在设置中删除这台手机。") }, confirmButton = { TextButton(onClick = { forget(); confirmForget = false; settings = false }) { Text("取消配对") } }, dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("保留") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空接收记录？") }, text = { Text("本应用中的记录和已下载附件会被删除，已另存到其他位置的文件不受影响。") }, confirmButton = { TextButton(onClick = { clear(); confirmClear = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("保留") } })
}

@Composable private fun ClipCard(clip: ReceivedClip, state: AppState, copy: (ReceivedClip) -> Unit, share: (ReceivedClip) -> Unit, save: (ReceivedClip) -> Unit, download: (ReceivedClip) -> Unit, cancelDownload: () -> Unit) {
    val copied = state.copiedId == clip.id
    val local = clip.kind == "text" || clip.fileName.isNotEmpty()
    val downloading = state.downloadingId == clip.id
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(21.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (clip.kind) { "text" -> Icons.Outlined.Subject; "image" -> Icons.Outlined.Image; else -> Icons.Outlined.InsertDriveFile }, null, Modifier.size(18.dp), tint = Blue)
            Text(when (clip.kind) { "text" -> "文字"; "image" -> "图片"; else -> "文件" }, Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = Muted)
            Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(clip.time)), fontSize = 11.sp, color = Muted)
        }
        if (clip.kind == "text") Text(clip.text.take(800), fontSize = 18.sp, lineHeight = 29.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        else Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { SystemFileIcon(clip); Text(clip.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis); Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, clip.size), color = Muted, fontSize = 13.sp) }
        if (clip.kind == "image" && (local || clip.thumbnail.isNotEmpty())) ImagePreview(clip)
        if (!local) {
            if (downloading) {
                val fraction = if (clip.size == 0L) 0f else (state.downloadedBytes.toDouble() / clip.size).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.cancelling) "正在取消…" else "下载中 · ${(fraction * 100).toInt()}%", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = cancelDownload, enabled = !state.cancelling) { Text("取消") }
                }
            } else {
                Button(onClick = { download(clip) }, modifier = Modifier.fillMaxWidth().height(51.dp), shape = RoundedCornerShape(13.dp), enabled = state.downloadingId == null && state.connected) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (!state.connected) "等待 Mac 连接后下载" else if (state.downloadErrors.containsKey(clip.id)) "重试下载" else "下载到手机")
                }
                Text("下载保存在应用内，完成后可另存到指定位置。", color = Muted, fontSize = 12.sp)
            }
            state.downloadErrors[clip.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        if (local) {
            Button(onClick = { copy(clip) }, Modifier.fillMaxWidth().height(51.dp), shape = RoundedCornerShape(13.dp)) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "已写入剪贴板 · 再次复制" else "复制到剪贴板", fontSize = 15.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { share(clip) }) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("分享", fontSize = 13.sp)
                }
                if (clip.kind != "text") {
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = { save(clip) }) {
                        Icon(Icons.Outlined.SaveAlt, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("另存为", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun ImagePreview(clip: ReceivedClip) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, clip.id, clip.fileName, clip.thumbnail) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (clip.fileName.isEmpty()) {
                    val bytes = java.util.Base64.getDecoder().decode(clip.thumbnail)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..1200 && bounds.outHeight in 1..1200)
                    return@runCatching android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                val file = File(Repository.get(context).directory, clip.fileName)
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 1200 || options.outHeight / options.inSampleSize > 1200) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), clip.name, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit) }
}


@Composable private fun SystemFileIcon(clip: ReceivedClip) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon by produceState<android.graphics.Bitmap?>(null, clip.mime) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = if (Build.VERSION.SDK_INT >= 29) context.contentResolver.getTypeInfo(clip.mime).icon.loadDrawable(context)
                    else context.getDrawable(android.R.drawable.ic_menu_save)
                drawable?.let {
                    val bitmap = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888)
                    it.setBounds(0, 0, 144, 144); it.draw(android.graphics.Canvas(bitmap)); bitmap
                }
            }.getOrNull()
        }
    }
    icon?.let { Image(it.asImageBitmap(), "文件类型", Modifier.size(48.dp)) }
}
