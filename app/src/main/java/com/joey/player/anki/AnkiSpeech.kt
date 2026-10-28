package com.joey.player.anki

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speaks Anki cards with the phone's own text-to-speech engine, so cards without
 * exported audio still play and the APK no longer ships 20 MB of narration.
 * Japanese runs are voiced with a Japanese voice and stitched into the same clip.
 */
class AnkiSpeech(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "anki_tts").apply { mkdirs() }
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var japaneseAvailable = false

    suspend fun clipFor(cardId: Long, text: String): Uri? = mutex.withLock {
        val clean = text.trim()
        if (clean.isBlank()) return@withLock null
        val target = File(cacheDir, "$cardId-${clean.hashCode().toUInt().toString(16)}.wav")
        if (target.length() > WAV_HEADER) return@withLock Uri.fromFile(target)
        val engine = engine() ?: return@withLock null
        val segments = splitByScript(clean).takeIf { japaneseAvailable } ?: listOf(clean to false)
        val parts = segments.mapIndexed { index, (segment, japanese) ->
            val part = File(cacheDir, "${target.nameWithoutExtension}.$index.part.wav")
            engine.language = if (japanese) Locale.JAPANESE else Locale.US
            if (!synthesize(engine, segment, part)) return@withLock null
            part
        }
        val merged = withContext(Dispatchers.IO) {
            if (parts.size == 1) parts.single().renameTo(target) else concatWav(parts, target)
        }
        parts.forEach { it.delete() }
        if (!merged) {
            // Voices disagreed on format: fall back to a single English pass.
            engine.language = Locale.US
            if (!synthesize(engine, clean, target)) return@withLock null
        }
        Uri.fromFile(target)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            var created: TextToSpeech? = null
            created = TextToSpeech(appContext) { status ->
                val engine = created
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    japaneseAvailable = engine.isLanguageAvailable(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE
                    engine.setSpeechRate(1.0f)
                    tts = engine
                    if (continuation.isActive) continuation.resume(engine)
                } else {
                    engine?.shutdown()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private suspend fun synthesize(engine: TextToSpeech, text: String, file: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = file.name
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(file.length() > WAV_HEADER)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(false)
                }
            })
            val result = engine.synthesizeToFile(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), Bundle(), file, utteranceId)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(false)
        }

    private data class WavFormat(val header: ByteArray, val formatKey: String, val dataOffset: Long, val dataLength: Long)

    private fun readWav(file: File): WavFormat? = RandomAccessFile(file, "r").use { input ->
        val riff = ByteArray(12)
        if (input.read(riff) != 12 || String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null
        var formatKey: String? = null
        var fmtEnd = 12L
        while (input.filePointer + 8 <= input.length()) {
            val chunkHeader = ByteArray(8)
            input.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4)
            val size = littleEndianInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteArray(size.toInt())
                input.readFully(fmt)
                formatKey = fmt.take(16).joinToString(",")
                fmtEnd = input.filePointer
            } else if (id == "data") {
                val header = ByteArray(fmtEnd.toInt())
                input.seek(0)
                input.readFully(header)
                val available = input.length() - (fmtEnd + 8)
                val length = if (size == 0L || size > available) available else size
                return WavFormat(header, formatKey ?: return null, fmtEnd + 8, length)
            } else {
                input.seek(input.filePointer + size + (size and 1))
            }
        }
        null
    }

    private fun concatWav(parts: List<File>, target: File): Boolean {
        val formats = parts.map { readWav(it) ?: return false }
        if (formats.map { it.formatKey }.distinct().size != 1) return false
        val header = formats.first().header
        val totalData = formats.sumOf { it.dataLength }
        RandomAccessFile(target, "rw").use { output ->
            output.setLength(0)
            output.write(header)
            output.write("data".toByteArray())
            output.write(littleEndian(totalData.toInt()))
            parts.zip(formats).forEach { (file, format) ->
                RandomAccessFile(file, "r").use { input ->
                    input.seek(format.dataOffset)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = format.dataLength
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.seek(4)
            output.write(littleEndian((output.length() - 8).toInt()))
        }
        return true
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun littleEndian(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    companion object {
        private const val WAV_HEADER = 44L

        /** Splits text into runs of Japanese (kana/kanji) and everything else. */
        fun splitByScript(text: String): List<Pair<String, Boolean>> {
            val runs = mutableListOf<Pair<StringBuilder, Boolean>>()
            text.forEach { char ->
                val japanese = isJapanese(char)
                val last = runs.lastOrNull()
                if (last != null && (last.second == japanese || char.isWhitespace() || (!char.isLetterOrDigit() && !japanese))) {
                    last.first.append(char)
                } else {
                    runs += StringBuilder().append(char) to japanese
                }
            }
            return runs.map { it.first.toString().trim() to it.second }.filter { it.first.isNotEmpty() }
        }

        private fun isJapanese(char: Char): Boolean =
            char in '぀'..'ヿ' || char in '一'..'鿿' || char in '㐀'..'䶿' || char in 'ｦ'..'ﾟ'
    }
}
