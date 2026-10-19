package com.copymore.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class Endpoint(val id: String, val host: String, val port: Int)

class SyncService : Service() {
    private lateinit var repo: Repository
    private lateinit var nsd: NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val endpoint = AtomicReference<Endpoint?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    private val uploads = Executors.newSingleThreadExecutor()
    @Volatile private var running = true
    @Volatile private var socket: SSLSocket? = null
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate(); repo = Repository.get(this); repo.event("后台服务创建")
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("connection", "设备连接", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notification("正在寻找你的 Mac…"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        repo.state.update { it.copy(serviceRunning = true) }
        nsd = getSystemService(NsdManager::class.java)
        multicast = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("CopyMore discovery").apply { setReferenceCounted(false); acquire() }
        discover()
        executor.execute { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            repo.store.enabled = false
            repo.state.update { it.copy(enabled = false, status = "后台常驻已关闭", connected = false) }
        }
        if (!repo.store.enabled || (repo.pendingTicket == null && repo.state.value.server == null)) {
            repo.event("用户暂停或尚未配对，停止服务")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        repo.event(if (intent == null) "系统恢复后台服务" else "后台常驻已启动")
        if (intent?.getBooleanExtra("reconnect", false) == true) runCatching { socket?.close() }
        return START_STICKY
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, SyncService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "connection").setSmallIcon(R.drawable.ic_copymore)
            .setContentTitle("CopyMore · 后台常驻").setContentText(status).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "暂停连接", pause).build()
    }
    private fun status(message: String, connected: Boolean = false, error: String? = null) {
        if (!running) return
        repo.status(message, connected, error)
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }

    @Suppress("DEPRECATION")
    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = repo.pendingTicket?.id ?: repo.state.value.server?.id ?: return
                if (info.serviceName != "CopyMore-$id") return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host?.hostAddress ?: return
                        endpoint.set(Endpoint(id, host, service.port))
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val old = endpoint.get()
                if (old != null && info.serviceName == "CopyMore-${old.id}") endpoint.compareAndSet(old, null)
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices("_copymore._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }

    private fun connectionLoop() {
        var attempt = 0
        while (running) {
            val ticket = repo.pendingTicket
            val trusted = repo.state.value.server
            if (!repo.store.enabled || (ticket == null && trusted == null)) { stopSelf(); return }
            val id = ticket?.id ?: trusted!!.id
            val fingerprint = ticket?.fingerprint ?: trusted!!.fingerprint
            val address = endpoint.get()?.takeIf { it.id == id } ?: Endpoint(id, ticket?.host ?: trusted!!.host, ticket?.port ?: trusted!!.port)
            var heartbeat: java.util.concurrent.ScheduledExecutorService? = null
            var transfer: IncomingTransfer? = null
            try {
                status(if (ticket != null) "正在配对…" else "正在自动连接…")
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(fingerprint)), null) }
                val raw = Socket()
                // Connect before wrapping; wrapping retains ownership and certificate pinning.
                try {
                    raw.connect(InetSocketAddress(address.host, address.port), 5000)
                    val tls = context.socketFactory.createSocket(raw, address.host, address.port, true) as SSLSocket
                    socket = tls
                    tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    tls.soTimeout = 12000; tls.startHandshake()
                } catch (failure: Exception) { raw.close(); throw failure }
                val tls = socket ?: throw IllegalStateException("连接已取消")
                if (!running) break
                val input = BufferedInputStream(tls.inputStream)
                val hello = JSONObject().put("type", if (ticket == null) "auth" else "pair").put("version", 2).put("deviceId", repo.store.deviceId)
                if (ticket != null) hello.put("secret", ticket.secret).put("name", Build.MODEL.take(80)) else hello.put("token", trusted!!.token)
                write(tls, hello)
                val response = readFrame(input)
                if (response.optString("type") == "error") {
                    repo.pendingTicket = null
                    repo.store.enabled = false
                    repo.state.update { it.copy(enabled = false) }
                    status("需要重新配对", error = "配对已失效，请在 Mac 上生成新的二维码后扫描。")
                    stopSelf(); return
                }
                require(response.getString("serverId") == id) { "电脑身份不匹配" }
                if (ticket != null) {
                    require(response.getString("type") == "paired")
                    val token = response.getString("token")
                    require(java.util.Base64.getDecoder().decode(token).size == 32)
                    if (!running || !repo.paired(TrustedServer(id, response.optString("name", ticket.name).take(80), address.host, address.port, fingerprint, token), ticket)) break
                } else require(response.getString("type") == "ready")
                val canPush = response.optJSONArray("features")?.let { features -> (0 until features.length()).any { features.optString(it) == "push-v1" } } == true
                repo.state.update { it.copy(canSend = canPush) }
                tls.soTimeout = 45000; attempt = 0
                status("已连接 · 同一 Wi-Fi", connected = true)
                repo.event("TLS 认证成功，局域网连接已建立")
                heartbeat = Executors.newSingleThreadScheduledExecutor()
                heartbeat.scheduleWithFixedDelay({
                    runCatching {
                        if (canPush) repo.sender.take()?.let { outgoing ->
                            if (outgoing.text != null) write(tls, JSONObject().put("type", "push_text").put("id", outgoing.id).put("text", outgoing.text))
                            else write(tls, JSONObject().put("type", "push_begin").put("id", outgoing.id).put("kind", outgoing.kind).put("name", outgoing.name).put("mime", outgoing.mime).put("size", outgoing.size))
                        }
                        while (true) {
                            val command = repo.commands.poll() ?: break
                            if (command.optString("id") == repo.state.value.downloadingId) write(tls, command)
                        }
                    }.onFailure { runCatching { tls.close() } }
                }, 0, 200, TimeUnit.MILLISECONDS)
                heartbeat.scheduleWithFixedDelay({ runCatching { write(tls, JSONObject().put("type", "ping")) }.onFailure { runCatching { tls.close() } } }, 15, 15, TimeUnit.SECONDS)
                var lastHeartbeatLog = 0L
                while (running && repo.state.value.server?.id == id && repo.pendingTicket == null) {
                    val message = readFrame(input)
                    if (!running || repo.state.value.server?.id != id || repo.pendingTicket != null) break
                    when (message.optString("type")) {
                        "push_ready" -> {
                            val uploadId = message.getString("id")
                            val outgoing = repo.sender.active?.takeIf { it.id == uploadId && it.file != null } ?: error("无效发送确认")
                            uploads.execute {
                                runCatching {
                                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                                    outgoing.file!!.inputStream().use { source ->
                                        val bytes = ByteArray(48 * 1024)
                                        while (running && socket === tls && repo.sender.active === outgoing) {
                                            val count = source.read(bytes); if (count < 0) break
                                            digest.update(bytes, 0, count)
                                            write(tls, JSONObject().put("type", "push_chunk").put("id", uploadId).put("data", java.util.Base64.getEncoder().encodeToString(bytes.copyOf(count))))
                                        }
                                    }
                                    if (running && socket === tls && repo.sender.active === outgoing) write(tls, JSONObject().put("type", "push_end").put("id", uploadId).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }))
                                }.onFailure {
                                    repo.sender.complete(uploadId, "发送失败，请重新分享文件")
                                    runCatching { write(tls, JSONObject().put("type", "push_cancel").put("id", uploadId)) }
                                }
                            }
                        }
                        "push_received" -> repo.sender.complete(message.getString("id"))
                        "push_error" -> repo.sender.complete(message.optString("id"), "Mac 未接收：" + message.optString("reason", "请重试"))
                        "clipboard" -> {
                            val clipId = message.getString("id"); UUID.fromString(clipId)
                            require(message.getString("kind") == "text")
                            val text = message.getString("text"); require(text.toByteArray().size <= MAX_TEXT)
                            repo.received(ReceivedClip(clipId, "text", "文字", "text/plain", text, size = text.toByteArray().size.toLong()))
                            write(tls, JSONObject().put("type", "received").put("id", clipId))
                        }
                        "attachment_offer" -> repo.received(attachmentOffer(message, id))
                        "download_error", "download_cancelled" -> {
                            val failedId = message.getString("id")
                            if (transfer?.id == failedId) { transfer?.close(); transfer = null }
                            repo.finishDownload(failedId, if (message.getString("type") == "download_error") message.optString("reason", "下载失败，请重试") else null)
                        }
                        "transfer_start" -> {
                            val requested = repo.state.value.clips.firstOrNull { it.id == repo.state.value.downloadingId }
                            require(requested != null && message.getString("id") == requested.id) { "收到未请求的文件" }
                            transfer?.close(); transfer = IncomingTransfer(message, repo.directory, requested)
                        }
                        "transfer_chunk" -> {
                            val active = transfer ?: error("未开始传输")
                            active.append(message); repo.progress(active.id, active.count)
                        }
                        "transfer_end" -> {
                            val clip = (transfer ?: error("未开始传输")).finish(message)
                            transfer?.close(); transfer = null; repo.received(clip.copy(serverId = id)); repo.finishDownload(clip.id)
                            write(tls, JSONObject().put("type", "received").put("id", clip.id))
                        }
                        "pong" -> {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastHeartbeatLog >= 60000) {
                                repo.event("连接心跳已确认")
                                lastHeartbeatLog = now
                            }
                        }
                        else -> error("无法识别的消息")
                    }
                }
            } catch (failure: Exception) {
                if (running) repo.disconnected(when (failure) {
                    is java.net.SocketTimeoutException -> "连接超时，等待网络恢复"
                    is java.io.EOFException -> "电脑关闭了连接"
                    is javax.net.ssl.SSLException -> "TLS 连接失败，请检查两端版本与配对"
                    is java.net.ConnectException -> "暂时无法连接电脑"
                    else -> "连接异常（${failure.javaClass.simpleName}）"
                })
                transfer?.let { failed -> socket?.let { tls -> runCatching { write(tls, JSONObject().put("type", "transfer_error").put("id", failed.id)) } } }
                if (running) status("等待 Mac 上线 · 将自动重连", error = when (failure) {
                    is javax.net.ssl.SSLException -> "无法验证电脑身份，请检查 Mac 或重新扫码。"
                    is IllegalArgumentException -> failure.message ?: "收到的内容未通过校验，已停止本次传输。"
                    else -> null
                })
            } finally {
                heartbeat?.shutdownNow(); transfer?.close(); repo.sender.disconnected()
                repo.state.update { it.copy(canSend = false) }
                runCatching { socket?.close() }; socket = null
                repo.state.value.downloadingId?.let { repo.finishDownload(it, "连接中断或下载失败，请重试；文件将重新下载") }
            }
            if (running) {
                // Refresh discovery as the laptop's address or port may have changed.
                discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
                Handler(Looper.getMainLooper()).post { if (running) discover() }
                val delay = minOf(15000L, 1000L shl minOf(attempt++, 4))
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun write(socket: SSLSocket, data: JSONObject) = synchronized(writeLock) {
        socket.outputStream.write((data.toString() + "\n").toByteArray(Charsets.UTF_8)); socket.outputStream.flush()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        repo.event("应用从最近任务移除，后台服务继续运行")
        if (!repo.store.enabled) stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        repo.event("后台服务结束")
        running = false; runCatching { socket?.close() }; executor.shutdownNow(); uploads.shutdownNow()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        multicast?.let { if (it.isHeld) it.release() }
        repo.state.updateConnectionStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_PAUSE = "com.copymore.android.PAUSE"
        fun start(context: Context, reconnect: Boolean = false) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java).putExtra("reconnect", reconnect))
        }
    }
}

private fun kotlinx.coroutines.flow.MutableStateFlow<AppState>.updateConnectionStopped() {
    update { state -> state.copy(connected = false, serviceRunning = false) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val repo = Repository.get(context)
        if (repo.store.enabled && repo.state.value.server != null) runCatching { SyncService.start(context) }
            .onFailure { repo.event("系统暂不允许恢复后台服务，请打开应用") }
    }
}
