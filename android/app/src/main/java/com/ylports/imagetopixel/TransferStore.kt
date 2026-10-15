package com.ylports.imagetopixel

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Chunked, bounded transfers. No unrestricted file paths cross the web bridge. */
class TransferStore(context: Context) : AutoCloseable {
    data class Entry(val file: File, val name: String, val type: String, val expected: Long, var stream: FileOutputStream?, var received: Long = 0)
    private val directory = File(context.cacheDir, "transfers").apply { mkdirs() }
    private val entries = ConcurrentHashMap<String, Entry>()

    init { directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() } }

    fun begin(name: String, type: String, size: Long): String {
        require(size >= 0 && size < directory.usableSpace - 16 * 1024 * 1024) { "Not enough free space for this file" }
        require(entries.size < 8) { "Too many transfers are already open" }
        val token = UUID.randomUUID().toString()
        val file = File(directory, token)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), "_").take(180).ifBlank { "pixel-art" }
        entries[token] = Entry(file, safeName, type.take(120), size, FileOutputStream(file))
        return token
    }

    fun chunk(token: String, encoded: String) {
        require(encoded.length <= 262144) { "Transfer chunk is too large" }
        val entry = entry(token)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(entry.received + bytes.size <= entry.expected) { "Transfer exceeds the announced size" }
        checkNotNull(entry.stream) { "Transfer is already closed" }.write(bytes)
        entry.received += bytes.size
    }

    fun finish(token: String) {
        val e = entry(token)
        require(e.received == e.expected) { "Incomplete file transfer" }
        e.stream?.close(); e.stream = null
    }

    fun register(file: File, name: String, type: String): String {
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(file, name, type, file.length(), null, file.length())
        return token
    }

    fun entry(token: String): Entry {
        require(Regex("[a-f0-9-]{36}").matches(token)) { "Invalid transfer token" }
        return requireNotNull(entries[token]) { "This temporary file has expired" }
    }

    fun ready(token: String): Entry = entry(token).also { check(it.stream == null) { "File transfer is not complete" } }
    fun discard(token: String) { entries.remove(token)?.let { it.stream?.close(); it.file.delete() } }
    fun response(token: String): WebResourceResponse? = runCatching {
        val e = ready(token)
        WebResourceResponse(e.type, null, 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), e.file.inputStream())
    }.getOrNull()

    override fun close() { entries.keys.toList().forEach(::discard) }
}
