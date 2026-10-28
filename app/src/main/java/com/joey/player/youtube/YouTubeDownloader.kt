package com.joey.player.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

enum class YouTubeMode { AUDIO, VIDEO }

/**
 * Downloads YouTube audio (m4a) or video (mp4, muxed on-device with MediaMuxer)
 * straight from InnerTube. Replaces the bundled Python + yt-dlp + ffmpeg runtime (~200 MB).
 */
class YouTubeDownloader(private val outputDir: File) {
    private data class Client(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private data class Stream(
        val url: String,
        val itag: Int,
        val mimeType: String,
        val bitrate: Int,
        val height: Int,
        val contentLength: Long,
    )

    private data class PlayerInfo(
        val title: String,
        val streams: List<Stream>,
        val client: Client,
    )

    suspend fun download(link: String, mode: YouTubeMode, onProgress: (Float?, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val videoId = parseVideoId(link) ?: error("That doesn't look like a YouTube link.")
            onProgress(null, "Looking up video...")
            val info = fetchPlayer(videoId)
            outputDir.mkdirs()
            val base = "${sanitize(info.title)}-$videoId"
            val audio = info.streams
                .filter { it.mimeType.startsWith("audio/mp4") }
                .maxByOrNull { it.bitrate }
                ?: error("No downloadable audio stream for this video.")

            val result = when (mode) {
                YouTubeMode.AUDIO -> {
                    val target = File(outputDir, "$base.m4a")
                    fetch(audio, info.client, target) { onProgress(it, "Downloading audio ${percent(it)}") }
                    target
                }
                YouTubeMode.VIDEO -> {
                    val video = info.streams
                        .filter { it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1") && it.height in 1..720 }
                        .maxWithOrNull(compareBy<Stream> { it.height }.thenBy { it.bitrate })
                        ?: error("No downloadable video stream for this video.")
                    val videoPart = File(outputDir, ".$videoId.video.part")
                    val audioPart = File(outputDir, ".$videoId.audio.part")
                    val total = (video.contentLength + audio.contentLength).coerceAtLeast(1L).toFloat()
                    try {
                        fetch(video, info.client, videoPart) {
                            onProgress(it * video.contentLength / total, "Downloading ${video.height}p video ${percent(it)}")
                        }
                        fetch(audio, info.client, audioPart) {
                            onProgress((video.contentLength + it * audio.contentLength) / total, "Downloading audio ${percent(it)}")
                        }
                        onProgress(null, "Merging...")
                        val target = File(outputDir, "$base.mp4")
                        mux(videoPart, audioPart, target)
                        target
                    } finally {
                        videoPart.delete()
                        audioPart.delete()
                    }
                }
            }
            runCatching { fetchThumbnail(videoId, File(outputDir, "$base.jpg")) }
            result
        }

    private fun fetchPlayer(videoId: String): PlayerInfo {
        var lastReason = "YouTube refused the request."
        for (client in clients) {
            val response = runCatching { postPlayer(client, videoId) }.getOrElse {
                lastReason = it.message ?: lastReason
                null
            } ?: continue
            val status = response.optJSONObject("playabilityStatus")
            if (status?.optString("status") != "OK") {
                lastReason = status?.optString("reason")?.takeIf { it.isNotBlank() } ?: lastReason
                continue
            }
            val streaming = response.optJSONObject("streamingData") ?: continue
            val streams = buildList {
                listOf("adaptiveFormats", "formats").forEach { key ->
                    val array = streaming.optJSONArray(key) ?: return@forEach
                    for (index in 0 until array.length()) {
                        val format = array.getJSONObject(index)
                        val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
                        add(
                            Stream(
                                url = url,
                                itag = format.optInt("itag"),
                                mimeType = format.optString("mimeType"),
                                bitrate = format.optInt("bitrate"),
                                height = format.optInt("height"),
                                contentLength = format.optString("contentLength").toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                }
            }
            if (streams.isEmpty()) continue
            val title = response.optJSONObject("videoDetails")?.optString("title").orEmpty().ifBlank { videoId }
            return PlayerInfo(title, streams, client)
        }
        throw IOException(lastReason)
    }

    private fun postPlayer(client: Client, videoId: String): JSONObject {
        val clientJson = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "en")
        client.extra.forEach { (key, value) -> clientJson.put(key, value) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientJson))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
        val connection = (URL(PLAYER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("X-YouTube-Client-Name", client.id)
            setRequestProperty("X-YouTube-Client-Version", client.version)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) throw IOException("YouTube returned HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Ranged chunks avoid YouTube's throttling of single long requests, and resume after a dropped chunk. */
    private suspend fun fetch(stream: Stream, client: Client, target: File, onProgress: (Float) -> Unit) {
        val total = stream.contentLength.takeIf { it > 0L } ?: probeLength(stream, client)
        val partial = File(target.parentFile, ".${target.name}.part")
        RandomAccessFile(partial, "rw").use { output ->
            output.setLength(0L)
            var offset = 0L
            val buffer = ByteArray(64 * 1024)
            while (offset < total) {
                coroutineContext.ensureActive()
                val end = minOf(offset + CHUNK - 1, total - 1)
                var attempt = 0
                while (true) {
                    try {
                        val connection = (URL(stream.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            setRequestProperty("User-Agent", client.userAgent)
                            setRequestProperty("Range", "bytes=$offset-$end")
                        }
                        try {
                            if (connection.responseCode !in 200..299) throw IOException("Stream returned HTTP ${connection.responseCode}")
                            output.seek(offset)
                            connection.inputStream.use { input ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    offset += read
                                    onProgress(offset.toFloat() / total)
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }
                        break
                    } catch (error: IOException) {
                        attempt += 1
                        if (attempt >= 4) throw error
                        kotlinx.coroutines.delay(1_000L * attempt)
                    }
                }
            }
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun probeLength(stream: Stream, client: Client): Long {
        val connection = (URL(stream.url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            connection.responseCode
            return connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw IOException("Unknown stream size")
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchThumbnail(videoId: String, target: File) {
        val connection = URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").openConnection() as HttpURLConnection
        try {
            if (connection.responseCode == 200) {
                connection.inputStream.use { input -> target.outputStream().use(input::copyTo) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun mux(videoFile: File, audioFile: File, target: File) {
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = listOf(videoFile, audioFile).map { file ->
            MediaExtractor().apply { setDataSource(file.absolutePath) }
        }
        try {
            val tracks = extractors.map { extractor ->
                extractor.selectTrack(0)
                muxer.addTrack(extractor.getTrackFormat(0))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { index, extractor ->
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(
                        0,
                        size,
                        extractor.sampleTime,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    muxer.writeSampleData(tracks[index], buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } catch (error: Exception) {
            target.delete()
            throw IOException("Could not merge video and audio: ${error.message}", error)
        } finally {
            extractors.forEach { it.release() }
            runCatching { muxer.release() }
        }
    }

    companion object {
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val CHUNK = 8L * 1024 * 1024
        private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
        private val linkPatterns = listOf(
            Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        )

        private val clients = listOf(
            Client(
                name = "IOS",
                id = "5",
                version = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                extra = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82",
                ),
            ),
            Client(
                name = "ANDROID",
                id = "3",
                version = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                extra = mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11"),
            ),
            Client(
                name = "ANDROID_VR",
                id = "28",
                version = "1.65.10",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                extra = mapOf("androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
        )

        fun parseVideoId(link: String): String? {
            val clean = link.trim()
            if (idPattern.matches(clean)) return clean
            return linkPatterns.firstNotNullOfOrNull { it.find(clean)?.groupValues?.get(1) }
        }

        /** Pulls the first YouTube link out of shared text such as "Check this out https://youtu.be/...". */
        fun extractLink(text: String): String? =
            Regex("""https?://\S+""").findAll(text).map { it.value }.firstOrNull { parseVideoId(it) != null }

        internal fun sanitize(title: String): String =
            title.replace(Regex("[^A-Za-z0-9._ -]"), "")
                .trim()
                .replace(Regex("\\s+"), "_")
                .take(120)
                .ifBlank { "video" }

        private fun percent(value: Float): String = "${(value * 100).toInt().coerceIn(0, 100)}%"
    }
}
