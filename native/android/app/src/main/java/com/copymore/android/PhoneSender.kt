package com.copymore.android

import android.content.*
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal data class OutgoingClip(val id: String = UUID.randomUUID().toString(), val text: String? = null,
    val file: File? = null, val name: String = "文字", val mime: String = "text/plain") {
    val kind get() = if (text != null) "text" else if (mime.startsWith("image/")) "image" else "file"
    val size get() = file?.length() ?: text.orEmpty().toByteArray().size.toLong()
}

/** Read only while our Activity has focus, or from an explicit Android share request. */
class PhoneSender(private val context: Context, private val repo: Repository) {
    private val executor = Executors.newSingleThreadExecutor()
    private val directory = File(context.cacheDir, "outgoing").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private val prefs = context.getSharedPreferences("outgoing-state", Context.MODE_PRIVATE)
    private val pending = AtomicReference<OutgoingClip?>(null)
    @Volatile internal var active: OutgoingClip? = null
        private set

    fun captureClipboard(explicit: Boolean = false) {
        if (repo.state.value.server == null || !repo.store.enabled) return
        runCatching {
            val data = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
            if (data.itemCount != 1) { if (explicit) problem("当前一次发送一个文件"); return }
            if (data.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            if (!explicit && data.description.extras?.getBoolean("com.copymore.from_mac", false) == true) return
            val item = data.getItemAt(0)
            val text = item.text?.toString()
            if (text?.startsWith("copymore://pair") == true) return
            val uri = item.uri
            if (!explicit && uri?.authority == "${context.packageName}.files") return
            val signature = sha256((text ?: uri?.toString() ?: return).toByteArray())
            if (!explicit && signature == prefs.getString("lastSeen", null)) return
            if (uri != null) prepareFile(uri) else if (text != null) prepareText(text) else return
            prefs.edit().putString("lastSeen", signature).apply()
        }.onFailure { problem("暂时无法读取手机剪贴板，请使用系统分享发送") }
    }

    fun share(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) prepareFile(uri) else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::prepareText)
    }

    private fun prepareText(text: String) {
        if (text.isEmpty()) return
        if (text.toByteArray().size > MAX_TEXT) { problem("文字超过 256 KB，请保存成文件后分享"); return }
        enqueue(OutgoingClip(text = text))
    }

    private fun prepareFile(uri: Uri) {
        if (uri.scheme != "content") { problem("请通过系统分享发送这个文件"); return }
        repo.state.update { it.copy(sendStatus = "正在准备发送…") }
        executor.execute {
            val file = File(directory, UUID.randomUUID().toString())
            runCatching {
                var name = "文件"; var declaredSize = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: "文件" }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
                    }
                }
                require(declaredSize <= (directory.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)) { "手机存储空间不足" }
                val mime = context.contentResolver.getType(uri)?.take(100) ?: "application/octet-stream"
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        check(directory.usableSpace > 16L * 1024 * 1024) { "手机存储空间不足" }
                        output.write(buffer, 0, count)
                    }
                } } ?: error("无法读取分享的文件")
                enqueue(OutgoingClip(file = file, name = name.substringAfterLast('/').substringAfterLast('\\').take(160), mime = mime))
            }.onFailure { file.delete(); problem("无法准备文件，请确认文件仍可访问且存储空间充足") }
        }
    }
    private fun enqueue(clip: OutgoingClip) {
        pending.getAndSet(clip)?.file?.delete()
        repo.state.update { it.copy(sendStatus = if (it.connected) "等待发送到 Mac…" else "等待 Mac 连接后发送") }
    }
    @Synchronized internal fun take(): OutgoingClip? {
        if (active != null) return null
        val item = pending.getAndSet(null) ?: return null
        active = item; repo.state.update { it.copy(sending = true, sendStatus = "正在发送到 Mac…") }; return item
    }
    @Synchronized internal fun complete(id: String, error: String? = null) {
        val item = active?.takeIf { it.id == id } ?: return
        active = null; item.file?.delete()
        repo.state.update { it.copy(sending = false, sendStatus = error ?: "已写入 Mac 剪贴板") }
    }
    internal fun disconnected() { active?.let { complete(it.id, "发送中断，请重新复制或分享后重试") } }
    fun clear() { pending.getAndSet(null)?.file?.delete(); disconnected() }
    private fun problem(message: String) { repo.state.update { it.copy(sendStatus = message) } }
}
