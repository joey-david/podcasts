package com.joey.player.domain

import android.webkit.MimeTypeMap
import java.util.Locale

object MediaSupport {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "m4v", "3gp")
    private val mimeFallbacks = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
    )

    fun isPlayableExtension(extension: String): Boolean {
        val normalized = extension.lowercase(Locale.US)
        return normalized in audioExtensions || normalized in videoExtensions
    }

    fun isVideoUri(uriString: String): Boolean = extensionOf(uriString) in videoExtensions

    fun inferMimeType(uriString: String): String? {
        val extension = extensionOf(uriString)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: mimeFallbacks[extension]
    }

    fun displayKind(uriString: String): String = if (isVideoUri(uriString)) "video" else "audio"

    private fun extensionOf(uriString: String): String =
        uriString.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.US)
}
