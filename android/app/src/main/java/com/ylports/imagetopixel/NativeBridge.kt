package com.ylports.imagetopixel

import android.content.ClipData
import android.content.ClipboardManager
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.math.min

class NativeBridge(private val activity: MainActivity) : AutoCloseable {
    private val videos = mutableMapOf<String, NativeVideoEncoder>()
    private val transfers get() = activity.transfers

    fun execute(method: String, args: JSONObject, reply: Reply) {
        when (method) {
            "save" -> activity.save(args.getString("token"), reply)
            "share" -> activity.share(args.getString("token"), false, reply)
            "shareUri" -> activity.shareUri(args.getString("uri"), args.optString("type", "application/octet-stream"), reply)
            "clipboardWrite" -> activity.share(args.getString("token"), true, reply)
            "clipboardText", "clipboardRead", "clipboardReadText" -> clipboard(method, args, reply)
            "ready" -> { activity.editorReady = true; reply(true, null) }
            else -> activity.io.execute {
                try {
                    val result: Any? = when (method) {
                        "begin" -> transfers.begin(args.getString("name"), args.getString("type"), args.getLong("size"))
                        "chunk" -> { transfers.chunk(args.getString("token"), args.getString("data")); true }
                        "finish" -> { transfers.finish(args.getString("token")); true }
                        "discard" -> { transfers.discard(args.getString("token")); true }
                        "videoSupport" -> {
                            val width = args.optInt("width", 640).let { it + (it and 1) }
                            val height = args.optInt("height", 480).let { it + (it and 1) }
                            JSONObject().put("mp4", NativeVideoEncoder.find("mp4", width, height) != null)
                                .put("webm", NativeVideoEncoder.find("webm", width, height) != null).put("webcodecs", false).put("native", true)
                        }
                        "videoStart" -> {
                            require(videos.isEmpty()) { "A video export is already in progress" }
                            val width = args.getInt("width"); val height = args.getInt("height")
                            val fps = args.optDouble("fps", 30.0)
                            val bitrate = args.optInt("bitrate", min(20e6, max(2e6, width.toDouble() * height * fps * 0.15)).toInt())
                            require(bitrate in 1..100_000_000) { "Invalid video bitrate" }
                            val token = UUID.randomUUID().toString()
                            videos[token] = NativeVideoEncoder(activity.cacheDir, args.getString("format"), width, height, fps, bitrate)
                            token
                        }
                        "videoFrame" -> {
                            val encoder = args.getString("encoder"); val token = args.getString("token")
                            try { checkNotNull(videos[encoder]) { "Video encoder is closed" }.add(transfers.ready(token).file, args.getInt("width"), args.getInt("height")) }
                            catch (e: Exception) { videos.remove(encoder)?.close(); throw e }
                            finally { transfers.discard(token) }
                            true
                        }
                        "videoFinish" -> {
                            val encoder = checkNotNull(videos.remove(args.getString("encoder"))) { "Video encoder is closed" }
                            try {
                                val file = encoder.finish()
                                val type = if (encoder.format == "mp4") "video/mp4" else "video/webm"
                                val token = transfers.register(file, "animation.${encoder.format}", type)
                                JSONObject().put("url", LocalContentClient.generated(token)).put("token", token)
                            } finally { encoder.close() }
                        }
                        "videoCancel" -> { videos.remove(args.getString("encoder"))?.close(); true }
                        "lospec" -> fetchPalette(args.getString("slug"))
                        else -> error("Unknown editor integration: $method")
                    }
                    reply(result, null)
                } catch (e: Exception) { reply(null, e.message ?: "Android operation failed") }
                catch (e: OutOfMemoryError) { close(); reply(null, "Not enough memory for this operation. Try closing other files first; image quality has not been reduced.") }
            }
        }
    }

    private fun clipboard(method: String, args: JSONObject, reply: Reply) {
        activity.runOnUiThread {
            try {
                val manager = activity.getSystemService(ClipboardManager::class.java)
                when (method) {
                    "clipboardText" -> {
                        val text = args.getString("text"); require(text.length < 1_000_000)
                        manager.setPrimaryClip(ClipData.newPlainText("Image-to-Pixel", text)); reply(true, null)
                    }
                    "clipboardReadText" -> reply(manager.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() ?: "", null)
                    else -> {
                        val uri = manager.primaryClip?.getItemAt(0)?.uri
                        if (uri == null || uri.scheme != "content") { reply(JSONObject.NULL, null); return@runOnUiThread }
                        val type = activity.contentResolver.getType(uri) ?: ""
                        require(type.startsWith("image/")) { "The clipboard has no image" }
                        activity.io.execute {
                            try {
                                val file = File.createTempFile("clipboard-", ".image", activity.cacheDir)
                                try {
                                    activity.contentResolver.openInputStream(uri).use { input -> checkNotNull(input); file.outputStream().use { input.copyTo(it) } }
                                    val token = transfers.register(file, "clipboard.png", type)
                                    reply(JSONObject().put("url", LocalContentClient.generated(token)).put("type", type).put("token", token), null)
                                } catch (e: Exception) { file.delete(); throw e }
                            } catch (e: Exception) { reply(null, "Could not read clipboard image: ${e.message}") }
                        }
                    }
                }
            } catch (e: Exception) { reply(null, e.message) }
        }
    }

    private fun fetchPalette(slug: String): JSONObject {
        require(Regex("[a-z0-9-]{1,100}").matches(slug)) { "Invalid Lospec slug" }
        // This is the sole optional network operation, initiated by the original Fetch button.
        val connection = URL("https://lospec.com/palette-list/$slug.json").openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Lospec needs a connection for this palette. Built-in and previously fetched palettes work offline. HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 1_048_576) { "Palette response is too large" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            require(bytes.size <= 1_048_576) { "Palette response is too large" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.has("colors")) { "Lospec returned an invalid palette" }
            return json
        } finally { connection.disconnect() }
    }

    override fun close() { videos.values.forEach { it.close() }; videos.clear() }
}
