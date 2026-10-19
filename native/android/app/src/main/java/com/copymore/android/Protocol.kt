package com.copymore.android

import org.json.JSONObject
import java.io.*
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.X509TrustManager

const val MAX_TEXT = 256 * 1024
const val MAX_FRAME = 2 * 1024 * 1024

data class PairingTicket(val id: String, val name: String, val host: String, val port: Int, val fingerprint: String, val secret: String) {
    companion object {
        fun parse(link: String): PairingTicket {
            require(link.length <= 4096) { "配对链接过长" }
            val uri = URI(link.trim())
            require(uri.scheme == "copymore" && uri.host == "pair") { "请扫描 CopyMore 的配对二维码" }
            val fields = (uri.rawQuery ?: "").split('&').associate {
                val pair = it.split('=', limit = 2)
                require(pair.size == 2) { "配对链接不完整" }
                pair[0] to URLDecoder.decode(pair[1].replace("+", "%2B"), "UTF-8")
            }
            require(fields["v"] == "2") { "需要更新 CopyMore 才能连接" }
            val id = fields.getValue("id"); UUID.fromString(id)
            val host = fields.getValue("host")
            require(host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 }) { "电脑地址无效" }
            val port = fields.getValue("port").toInt(); require(port in 1..65535)
            val fp = fields.getValue("fp").lowercase(); require(fp.matches(Regex("[a-f0-9]{64}")))
            val secret = fields.getValue("key"); require(Base64.getDecoder().decode(secret).size == 32)
            return PairingTicket(id, fields["name"]?.take(80) ?: "我的 Mac", host, port, fp, secret)
        }
    }
}

data class TrustedServer(val id: String, val name: String, val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("fp", fingerprint).put("token", token)
    companion object { fun from(json: JSONObject) = TrustedServer(json.getString("id"), json.getString("name"), json.getString("host"), json.getInt("port"), json.getString("fp"), json.getString("token")) }
}

class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("Client certificates are not supported")
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing certificate")
        leaf.checkValidity()
        val actual = sha256(leaf.encoded)
        if (!MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) throw java.security.cert.CertificateException("电脑身份已改变，请重新扫码配对")
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A bounded line reader: BufferedReader.readLine() could allocate without limit. */
fun readFrame(input: InputStream): JSONObject {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte == -1) throw EOFException("连接已断开")
        if (byte == 10) return JSONObject(buffer.toString("UTF-8"))
        if (buffer.size() >= MAX_FRAME) throw IOException("消息超过上限")
        buffer.write(byte)
    }
}

data class ReceivedClip(val id: String, val kind: String, val name: String, val mime: String, val text: String = "", val fileName: String = "", val size: Long = 0, val time: Long = System.currentTimeMillis(), val serverId: String = "", val thumbnail: String = "") {
    fun json() = JSONObject().put("id", id).put("kind", kind).put("name", name).put("mime", mime).put("text", text).put("fileName", fileName).put("size", size).put("time", time).put("serverId", serverId).put("thumbnail", thumbnail)
    companion object { fun from(j: JSONObject) = ReceivedClip(j.getString("id"), j.getString("kind"), j.getString("name"), j.getString("mime"), j.optString("text"), j.optString("fileName"), j.getLong("size"), j.getLong("time"), j.optString("serverId"), j.optString("thumbnail").take(131072)) }
}

fun attachmentOffer(message: JSONObject, serverId: String): ReceivedClip {
    val id = message.getString("id").also { require(UUID.fromString(it).toString().equals(it, true)) }
    val kind = message.getString("kind").also { require(it == "image" || it == "file") }
    val size = message.getLong("size").also { require(it >= 0) }
    val name = message.getString("name").substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(180).ifBlank { "文件" }
    val mime = message.optString("mime", "application/octet-stream").take(100)
    val thumbnail = message.optString("thumbnail")
    require(thumbnail.length <= 131072) { "图片预览超过上限" }
    return ReceivedClip(id, kind, name, mime, size = size, serverId = serverId, thumbnail = thumbnail)
}

class IncomingTransfer(header: JSONObject, private val directory: File, expected: ReceivedClip? = null) : Closeable {
    val id = header.getString("id").also { require(UUID.fromString(it).toString().equals(it, true)) }
    val size = header.getLong("size").also { require(it >= 0) }
    private val kind = header.getString("kind").also { require(it == "file" || it == "image") }
    private val name = header.getString("name").substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(180).ifBlank { "文件" }
    private val mime = header.optString("mime", "application/octet-stream").take(100)
    private val partial = File(directory, "$id.part")
    private val destination = File(directory, id)
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set
    private var finished = false
    private val output: FileOutputStream
    init {
        directory.mkdirs()
        if (expected != null) require(id == expected.id && size == expected.size && kind == expected.kind && mime == expected.mime) { "文件信息已改变，请重新复制" }
        require(size <= (directory.usableSpace - 16L * 1024 * 1024).coerceAtLeast(0)) { "手机存储空间不足，请清理空间后重试" }
        output = FileOutputStream(partial)
    }
    fun append(message: JSONObject) {
        require(message.getString("id") == id)
        val encoded = message.getString("data"); require(encoded.length <= 65536)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size.toLong() <= size - count) { "文件大小不一致" }
        output.write(bytes); digest.update(bytes); count += bytes.size
    }
    fun finish(message: JSONObject): ReceivedClip {
        require(message.getString("id") == id && count == size) { "文件传输不完整" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == message.getString("sha256")) { "文件校验失败" }
        output.flush(); output.fd.sync(); output.close()
        check(partial.renameTo(destination)) { "无法保存接收的文件" }
        finished = true
        return ReceivedClip(id, kind, name, mime, fileName = destination.name, size = size)
    }
    override fun close() { runCatching { output.close() }; if (!finished) partial.delete() }
}
