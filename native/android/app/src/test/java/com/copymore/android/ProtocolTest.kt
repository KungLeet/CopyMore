package com.copymore.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class ProtocolTest {
    private val id = UUID.randomUUID().toString()
    @Test fun parsesSwiftRFC3986TicketWithoutCorruptingPlus() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 0xfb.toByte() })
        val ticket = PairingTicket.parse("copymore://pair?v=2&id=$id&name=My%20Mac&host=192.168.1.5&port=5544&fp=${"a".repeat(64)}&key=$secret")
        assertEquals(secret, ticket.secret); assertEquals("My Mac", ticket.name); assertEquals(5544, ticket.port)
    }
    @Test fun rejectsUntrustedLinkAndInvalidAddress() {
        assertThrows(IllegalArgumentException::class.java) { PairingTicket.parse("https://example.com/pair") }
        assertThrows(IllegalArgumentException::class.java) { PairingTicket.parse("copymore://pair?v=2&id=$id&host=999.0.0.1&port=5&fp=${"a".repeat(64)}&key=x") }
    }
    @Test fun boundsFrameAllocationAndHandlesUnicode() {
        assertEquals("你好", readFrame(ByteArrayInputStream("{\"text\":\"你好\"}\n".toByteArray())).getString("text"))
        assertThrows(java.io.IOException::class.java) { readFrame(ByteArrayInputStream(ByteArray(MAX_FRAME + 1) { 65 })) }
    }
    private fun header(bytes: ByteArray, hash: String = sha256(bytes)) = JSONObject().put("id", id).put("kind", "file").put("name", "../../private.txt").put("mime", "text/plain").put("size", bytes.size).put("sha256", hash)
    @Test fun verifiesChunksAndDoesNotUseIncomingFilenameAsPath() {
        val dir = Files.createTempDirectory("copymore-test").toFile()
        try {
            val bytes = "内容".repeat(100).toByteArray()
            IncomingTransfer(header(bytes), dir).use { transfer ->
                transfer.append(JSONObject().put("id", id).put("data", Base64.getEncoder().encodeToString(bytes)))
                val clip = transfer.finish(JSONObject().put("id", id).put("sha256", sha256(bytes)))
                assertEquals("private.txt", clip.name); assertEquals(id, clip.fileName)
                assertArrayEquals(bytes, java.io.File(dir, clip.fileName).readBytes())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun deletesPartialFileOnCorruption() {
        val dir = Files.createTempDirectory("copymore-test").toFile()
        try {
            IncomingTransfer(header(byteArrayOf(1, 2), "0".repeat(64)), dir).use { transfer ->
                transfer.append(JSONObject().put("id", id).put("data", "AQI="))
                assertThrows(IllegalArgumentException::class.java) { transfer.finish(JSONObject().put("id", id).put("sha256", "0".repeat(64))) }
            }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsOverrunAndWrongTransferId() {
        val dir = Files.createTempDirectory("copymore-test").toFile()
        try {
            IncomingTransfer(header(byteArrayOf(1)), dir).use { transfer ->
                assertThrows(IllegalArgumentException::class.java) { transfer.append(JSONObject().put("id", id).put("data", "AQI=")) }
                assertThrows(IllegalArgumentException::class.java) { transfer.append(JSONObject().put("id", UUID.randomUUID().toString()).put("data", "AQ==")) }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun accepts64BitAttachmentMetadataWithoutAllocatingPayload() {
        val size = 3L * 1024 * 1024 * 1024 + 7
        val offer = attachmentOffer(header(byteArrayOf()).put("size", size), "mac")
        assertEquals(size, offer.size); assertEquals("", offer.fileName); assertEquals("mac", offer.serverId)
    }
    @Test fun rejectsChangedMetadataAndInsufficientSpace() {
        val dir = Files.createTempDirectory("copymore-test").toFile()
        try {
            val expected = attachmentOffer(header(byteArrayOf(1)), "mac")
            assertThrows(IllegalArgumentException::class.java) { IncomingTransfer(header(byteArrayOf(1, 2)), dir, expected) }
            assertThrows(IllegalArgumentException::class.java) { IncomingTransfer(header(byteArrayOf()).put("size", Long.MAX_VALUE), dir) }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun closingCancelledTransferRemovesPartialFile() {
        val dir = Files.createTempDirectory("copymore-test").toFile()
        try {
            IncomingTransfer(header(byteArrayOf(1, 2)), dir).use { transfer ->
                transfer.append(JSONObject().put("id", id).put("data", "AQ=="))
                assertEquals(1L, transfer.count)
            }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

}
