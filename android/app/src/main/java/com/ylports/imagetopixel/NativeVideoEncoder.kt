package com.ylports.imagetopixel

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/** WebView's missing WebCodecs integration. All pixel processing stays in JS. */
class NativeVideoEncoder(
    directory: File, val format: String, val width: Int, val height: Int,
    private val fps: Double, bitrate: Int,
) : AutoCloseable {
    private val choice = requireNotNull(find(format, width, height)) { "No $format encoder supports $width × $height on this device" }
    val output: File = File.createTempFile("encoded-", ".$format", directory)
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var frames = 0L
    private var finished = false
    private val info = MediaCodec.BufferInfo()

    init {
        require(fps.isFinite() && fps > 0 && fps <= 240) { "Invalid frame rate" }
        try {
            val mediaFormat = MediaFormat.createVideoFormat(choice.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.color)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            codec = MediaCodec.createByCodecName(choice.name).also { it.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start() }
            muxer = MediaMuxer(output.absolutePath, if (format == "mp4") MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        } catch (e: Exception) { close(); throw e }
    }

    private fun input(): Int {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = checkNotNull(codec).dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(false)
        }
        error("The Android video encoder stopped accepting frames")
    }

    fun add(file: File, sourceWidth: Int, sourceHeight: Int) {
        require(sourceWidth in 1..width && sourceHeight in 1..height)
        require(file.length() == sourceWidth.toLong() * sourceHeight * 4) { "Incomplete RGBA frame" }
        val c = checkNotNull(codec)
        val index = input()
        RandomAccessFile(file, "r").use { raw ->
            val rgba = raw.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            fun rgb(x: Int, y: Int): Int {
                if (x >= sourceWidth || y >= sourceHeight) return 0
                val p = (y * sourceWidth + x) * 4
                val alpha = rgba.get(p + 3).toInt() and 255
                val r = ((rgba.get(p).toInt() and 255) * alpha + 127) / 255
                val g = ((rgba.get(p + 1).toInt() and 255) * alpha + 127) / 255
                val b = ((rgba.get(p + 2).toInt() and 255) * alpha + 127) / 255
                return (r shl 16) or (g shl 8) or b
            }
            val image = c.getInputImage(index)
            if (image != null) {
                try {
                    val p = image.planes
                    for (y in 0 until height) for (x in 0 until width) {
                        val a = rgb(x, y)
                        p[0].buffer.put(y * p[0].rowStride + x * p[0].pixelStride, luma(a).toByte())
                    }
                    for (y in 0 until height step 2) for (x in 0 until width step 2) {
                        val a = average(rgb(x, y), rgb(x + 1, y), rgb(x, y + 1), rgb(x + 1, y + 1))
                        p[1].buffer.put(y / 2 * p[1].rowStride + x / 2 * p[1].pixelStride, chromaU(a).toByte())
                        p[2].buffer.put(y / 2 * p[2].rowStride + x / 2 * p[2].pixelStride, chromaV(a).toByte())
                    }
                } finally { image.close() }
            } else {
                require(choice.color != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) { "This encoder does not expose its YUV input layout" }
                val buffer = checkNotNull(c.getInputBuffer(index)); buffer.clear()
                for (y in 0 until height) for (x in 0 until width) buffer.put(luma(rgb(x, y)).toByte())
                val uv = width * height
                for (yy in 0 until height step 2) for (xx in 0 until width step 2) {
                    val a = average(rgb(xx, yy), rgb(xx + 1, yy), rgb(xx, yy + 1), rgb(xx + 1, yy + 1))
                    val i = yy / 2 * (width / 2) + xx / 2
                    if (choice.color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        buffer.put(uv + i * 2, chromaU(a).toByte()); buffer.put(uv + i * 2 + 1, chromaV(a).toByte())
                    } else {
                        buffer.put(uv + i, chromaU(a).toByte()); buffer.put(uv + uv / 4 + i, chromaV(a).toByte())
                    }
                }
            }
        }
        c.queueInputBuffer(index, 0, width * height * 3 / 2, (frames * 1_000_000 / fps).toLong(), 0)
        frames++; drain(false)
    }

    private fun drain(end: Boolean) {
        val c = checkNotNull(codec)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val index = c.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return; continue }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(track < 0) { "Video encoder changed format twice" }
                track = checkNotNull(muxer).addTrack(c.outputFormat); checkNotNull(muxer).start()
            } else if (index >= 0) {
                val buffer = checkNotNull(c.getOutputBuffer(index))
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    check(track >= 0) { "Video format is not ready" }
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    checkNotNull(muxer).writeSampleData(track, buffer, info)
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                c.releaseOutputBuffer(index, false)
                if (eos) return
            }
        }
        error("The Android video encoder did not finish in time")
    }

    fun finish(): File {
        check(frames > 0) { "There are no video frames to encode" }
        checkNotNull(codec).queueInputBuffer(input(), 0, 0, (frames * 1_000_000 / fps).toLong(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        checkNotNull(muxer).stop(); muxer?.release(); muxer = null
        codec?.stop(); codec?.release(); codec = null
        finished = true
        return output
    }

    override fun close() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { muxer?.release() }; muxer = null
        if (!finished) output.delete()
    }

    companion object {
        data class Choice(val name: String, val mime: String, val color: Int)
        fun find(format: String, width: Int, height: Int): Choice? {
            if (format !in setOf("mp4", "webm") || width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 || width.toLong() * height > Int.MAX_VALUE / 4) return null
            val mime = if (format == "mp4") "video/avc" else "video/x-vnd.on2.vp8"
            for (c in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }) {
                val cap = runCatching { c.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                if (!cap.videoCapabilities.isSizeSupported(width, height)) continue
                for (color in intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar))
                    if (color in cap.colorFormats) return Choice(c.name, mime, color)
            }
            return null
        }
        private fun average(a: Int, b: Int, c: Int, d: Int): Int {
            fun avg(shift: Int) = (((a shr shift) and 255) + ((b shr shift) and 255) + ((c shr shift) and 255) + ((d shr shift) and 255) + 2) / 4
            return (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
        }
        private fun luma(a: Int) = (((66 * (a shr 16) + 129 * ((a shr 8) and 255) + 25 * (a and 255) + 128) shr 8) + 16).coerceIn(0, 255)
        private fun chromaU(a: Int) = (((-38 * (a shr 16) - 74 * ((a shr 8) and 255) + 112 * (a and 255) + 128) shr 8) + 128).coerceIn(0, 255)
        private fun chromaV(a: Int) = (((112 * (a shr 16) - 94 * ((a shr 8) and 255) - 18 * (a and 255) + 128) shr 8) + 128).coerceIn(0, 255)
    }
}
