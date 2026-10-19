package com.copymore.android

import android.content.*
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("copymore.connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("copymore.connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    val deviceId: String get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("deviceId", it).commit()) }
    fun save(server: TrustedServer) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(server.json().toString().toByteArray())
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + encrypted)
        check(prefs.edit().putString("server", encoded).commit()) { "配对信息无法保存" }
    }
    fun load(): TrustedServer? {
        val encoded = prefs.getString("server", null) ?: return null
        val bytes = Base64.getDecoder().decode(encoded); require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return TrustedServer.from(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)))
    }
    fun forget() { check(prefs.edit().remove("server").commit()) }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
}

data class AppState(val server: TrustedServer? = null, val connected: Boolean = false, val status: String = "连接你的 Mac", val clips: List<ReceivedClip> = emptyList(), val copiedId: String? = null, val error: String? = null, val enabled: Boolean = true, val downloadingId: String? = null, val downloadedBytes: Long = 0, val cancelling: Boolean = false, val downloadErrors: Map<String, String> = emptyMap(), val sendStatus: String? = null, val canSend: Boolean = false, val sending: Boolean = false, val serviceRunning: Boolean = false, val lastDisconnect: String? = null)

class Repository private constructor(private val context: Context) {
    val store = SecureStore(context)
    val directory = File(context.filesDir, "received").apply { mkdirs() }
    private val journal = File(context.filesDir, "history.json")
    val state = MutableStateFlow(AppState(enabled = store.enabled))
    val sender = PhoneSender(context, this)
    @Volatile var pendingTicket: PairingTicket? = null
    val commands = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private var pendingAutoDownload: ReceivedClip? = null
    init {
        runCatching { store.load() }.onSuccess { saved -> state.update { it.copy(server = saved, status = if (saved == null) "连接你的 Mac" else "正在寻找你的 Mac…") } }
            .onFailure { state.update { it.copy(error = "配对信息无法读取，请重新扫码") } }
        runCatching {
            val array = JSONArray(journal.takeIf { it.exists() }?.readText() ?: "[]")
            val history = (0 until array.length()).map { ReceivedClip.from(array.getJSONObject(it)) }
                .map { if (it.kind != "text" && (it.fileName != it.id || !File(directory, it.fileName).isFile)) it.copy(fileName = "") else it }.take(20)
            state.update { it.copy(clips = history) }
        }
        directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
    }
    @Synchronized fun paired(server: TrustedServer, ticket: PairingTicket): Boolean {
        if (pendingTicket !== ticket) return false
        store.save(server); pendingTicket = null; state.update { it.copy(server = server, error = null) }; return true
    }
    @Synchronized fun event(message: String) {
        // Fixed event descriptions only: never log clipboard contents, names or pairing credentials.
        android.util.Log.i("CopyMoreConnection", message)
        // Some OEMs suppress app logcat output. Keep a bounded app-private diagnostic journal.
        runCatching {
            val file = File(context.filesDir, "connection-events.log")
            if (file.length() > 32768) file.writeText(file.readLines().takeLast(100).joinToString("\n", postfix = "\n"))
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
    fun disconnected(reason: String) {
        val label = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + " · " + reason
        state.update { it.copy(lastDisconnect = label) }; event("连接中断: " + reason)
    }
    fun status(message: String, connected: Boolean = false, error: String? = null) { state.update { it.copy(status = message, connected = connected, error = error) } }
    @Synchronized fun received(clip: ReceivedClip) {
        val old = state.value.clips.firstOrNull { it.id == clip.id }
        if (old != null && (clip.fileName.isEmpty() || old.fileName.isNotEmpty())) return
        val item = if (old == null) clip else clip.copy(time = old.time, serverId = old.serverId, thumbnail = clip.thumbnail.ifEmpty { old.thumbnail })
        val active = state.value.clips.firstOrNull { it.id == state.value.downloadingId && it.id != item.id }
        val candidates = listOf(item) + state.value.clips.filter { it.id != item.id && it.id != active?.id }
        val history = if (active == null) candidates.take(20) else candidates.take(19) + active
        val temp = File(journal.parentFile, "history.tmp")
        temp.writeText(JSONArray(history.map { it.json() }).toString()); check(temp.renameTo(journal))
        state.update { it.copy(clips = history, copiedId = if (item.kind == "text") null else it.copiedId) }
        val retained = history.map { it.fileName }.toSet()
        directory.listFiles()?.filter { it.extension != "part" && it.name !in retained }?.forEach { it.delete() }
        if (item.kind == "text" || item.fileName.isNotEmpty()) Handler(Looper.getMainLooper()).post { copy(item) }
        else if (item.kind == "image" || item.size <= 8L * 1024 * 1024) {
            if (state.value.downloadingId == null) requestDownload(item) else pendingAutoDownload = item
        }
    }
    @Synchronized fun requestDownload(clip: ReceivedClip) {
        val current = state.value
        if (current.downloadingId != null) return
        val reason = when {
            !current.connected -> "请等待 Mac 连接后再下载"
            clip.serverId != current.server?.id -> "请连接提供此文件的 Mac，或重新复制文件"
            clip.size > (directory.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0) -> "手机存储空间不足，请清理空间后重试"
            else -> null
        }
        if (reason != null) { state.update { it.copy(downloadErrors = it.downloadErrors + (clip.id to reason)) }; return }
        state.update { it.copy(downloadingId = clip.id, downloadedBytes = 0, cancelling = false, downloadErrors = it.downloadErrors - clip.id) }
        commands.add(JSONObject().put("type", "download").put("id", clip.id))
    }
    @Synchronized fun cancelDownload() {
        val id = state.value.downloadingId ?: return
        state.update { it.copy(cancelling = true) }
        commands.add(JSONObject().put("type", "cancel_download").put("id", id))
    }
    fun progress(id: String, count: Long) { state.update { if (it.downloadingId == id) it.copy(downloadedBytes = count) else it } }
    @Synchronized fun finishDownload(id: String, error: String? = null) {
        if (state.value.downloadingId != id) return
        commands.clear()
        state.update { it.copy(downloadingId = null, downloadedBytes = 0, cancelling = false, downloadErrors = if (error == null) it.downloadErrors - id else it.downloadErrors + (id to error)) }
        val next = pendingAutoDownload; pendingAutoDownload = null
        if (next != null && state.value.connected) requestDownload(next)
    }
    fun uri(clip: ReceivedClip) = FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, clip.fileName), clip.name)
    fun copy(clip: ReceivedClip) {
        runCatching {
            check(clip.kind == "text" || clip.fileName.isNotEmpty()) { "请先下载文件" }
            val data = if (clip.kind == "text") ClipData.newPlainText("来自 Mac", clip.text)
                else ClipData.newUri(context.contentResolver, clip.name, uri(clip))
            data.description.extras = android.os.PersistableBundle().apply { putBoolean("com.copymore.from_mac", true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(data)
            state.update { it.copy(copiedId = clip.id, error = null) }
        }.onFailure { state.update { it.copy(error = "内容已接收，系统未能写入剪贴板。请打开后点击复制或分享。") } }
    }
    @Synchronized fun clearHistory() {
        if (state.value.downloadingId != null) { state.update { it.copy(error = "请先取消或完成当前下载") }; return }
        state.update { it.copy(clips = emptyList(), copiedId = null) }; journal.delete()
        directory.listFiles()?.filter { it.extension != "part" }?.forEach { it.delete() }
    }
    @Synchronized fun forget() { sender.clear(); store.forget(); pendingTicket = null; state.update { it.copy(server = null, connected = false, status = "连接你的 Mac", error = null) } }
    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }
}
