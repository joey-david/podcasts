package com.joey.player

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
import com.joey.player.domain.MediaSupport
import com.joey.player.youtube.YouTubeDownloader
import com.joey.player.youtube.YouTubeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class PlayerRepository(
    private val context: Context,
) {
    private val separator = "\u0001"
    private val youtubeFolderName = "YouTube"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("slate_player", Context.MODE_PRIVATE)
    private val amoledState = MutableStateFlow(prefs.getBoolean(PlayerKeys.amoled, false))
    private val resumeState = MutableStateFlow(loadResume())

    val amoledFlow: Flow<Boolean> = amoledState
    val resumeFlow: Flow<ResumeState?> = resumeState

    suspend fun getFolders(): List<MediaFolder> {
        val raw = prefs.getString(PlayerKeys.folders, "").orEmpty()
        return raw.split("\n")
            .filter { it.isNotBlank() && it.contains(separator) }
            .map {
                val (uri, name) = it.split(separator, limit = 2)
                MediaFolder(uri, name)
            }
    }

    suspend fun addFolder(uri: Uri, name: String) {
        val encoded = encodeFolder(MediaFolder(uri.toString(), name))
        val current = prefs.getString(PlayerKeys.folders, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .toMutableSet()
        current.removeAll { it.startsWith(uri.toString() + separator) }
        current += encoded
        prefs.edit().putString(PlayerKeys.folders, current.sorted().joinToString("\n")).apply()
    }

    suspend fun removeFolder(folder: MediaFolder) {
        val current = prefs.getString(PlayerKeys.folders, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith(folder.uriString + separator) }
        prefs.edit().putString(PlayerKeys.folders, current.joinToString("\n")).apply()
    }

    suspend fun setAmoled(enabled: Boolean) {
        prefs.edit().putBoolean(PlayerKeys.amoled, enabled).apply()
        amoledState.value = enabled
    }

    suspend fun saveResume(resume: ResumeState) {
        prefs.edit()
            .putString(PlayerKeys.lastUri, resume.uriString)
            .putString(PlayerKeys.lastTitle, resume.title)
            .putLong(PlayerKeys.lastPosition, resume.positionMs)
            .putLong(PlayerKeys.lastDuration, resume.durationMs)
            .putBoolean(PlayerKeys.lastIsVideo, resume.isVideo)
            .apply()
        resumeState.value = resume
    }

    suspend fun getPlaybackPrefs(): PlaybackPrefs {
        val storedRepeatMode = prefs.getInt(PlayerKeys.repeatMode, Player.REPEAT_MODE_OFF)
        return PlaybackPrefs(
            speed = prefs.getFloat(PlayerKeys.playbackSpeed, 1f).coerceIn(0.5f, 3f),
            shuffleEnabled = prefs.getBoolean(PlayerKeys.shuffleEnabled, false),
            repeatMode = storedRepeatMode.takeIf {
                it == Player.REPEAT_MODE_OFF ||
                    it == Player.REPEAT_MODE_ONE ||
                    it == Player.REPEAT_MODE_ALL
            } ?: Player.REPEAT_MODE_OFF,
        )
    }

    suspend fun savePlaybackPrefs(prefs: PlaybackPrefs) {
        this.prefs.edit()
            .putFloat(PlayerKeys.playbackSpeed, prefs.speed)
            .putBoolean(PlayerKeys.shuffleEnabled, prefs.shuffleEnabled)
            .putInt(PlayerKeys.repeatMode, prefs.repeatMode)
            .apply()
    }

    suspend fun clearResume() {
        prefs.edit()
            .remove(PlayerKeys.lastUri)
            .remove(PlayerKeys.lastTitle)
            .remove(PlayerKeys.lastPosition)
            .remove(PlayerKeys.lastDuration)
            .remove(PlayerKeys.lastIsVideo)
            .apply()
        resumeState.value = null
    }

    private fun loadResume(): ResumeState? {
        val uri = prefs.getString(PlayerKeys.lastUri, null) ?: return null
        return ResumeState(
            uriString = uri,
            title = prefs.getString(PlayerKeys.lastTitle, "Last media") ?: "Last media",
            positionMs = prefs.getLong(PlayerKeys.lastPosition, 0L),
            durationMs = prefs.getLong(PlayerKeys.lastDuration, 0L),
            isVideo = prefs.getBoolean(PlayerKeys.lastIsVideo, false),
        )
    }

    suspend fun deleteMedia(entry: MediaEntry): Boolean = withContext(Dispatchers.IO) {
        if (!entry.deletable) return@withContext false
        val uri = Uri.parse(entry.uriString)
        when (uri.scheme) {
            "file" -> uri.path?.let { path ->
                val file = File(path)
                File(file.parentFile, file.nameWithoutExtension + ".jpg").delete()
                file.delete()
            } == true
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            else -> false
        }
    }

    suspend fun downloadYoutube(
        url: String,
        mode: YouTubeMode,
        onProgress: (Float?, String) -> Unit,
    ): File = YouTubeDownloader(youtubeDirectory()).download(url, mode, onProgress)

    suspend fun scanMedia(folders: List<MediaFolder>): List<MediaEntry> = withContext(Dispatchers.IO) {
        val folderMedia = folders.flatMap { folder ->
            scanIndexedMedia(folder) ?: run {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString)) ?: return@flatMap emptyList()
                scanFolder(root, folder.name)
            }
        }
        (scanYoutubeFolder() + folderMedia).sortedWith(compareBy(MediaEntry::folderName, MediaEntry::title))
    }

    suspend fun loadAnkiAudioCards(): AnkiAudioLibrary = withContext(Dispatchers.IO) {
        val importedRoot = findImportedAnkiRoot()
        val importedFiles = importedRoot?.listFiles()
            ?.filter { it.isFile }
            ?.associate { it.name.orEmpty() to it.uri.toString() }
            .orEmpty()
        val liveResult = runCatching { loadAnkiDroidAudioCards(importedFiles) }
        liveResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { liveCards ->
            return@withContext AnkiAudioLibrary(
                cards = liveCards.sortedWith(compareBy(AnkiAudioCard::category, AnkiAudioCard::title)),
                source = "AnkiDroid",
            )
        }
        val imported = importedRoot?.let(::loadAnkiAudioCardsFromDocumentRoot).orEmpty()
        AnkiAudioLibrary(
            cards = imported.sortedWith(compareBy(AnkiAudioCard::category, AnkiAudioCard::title)),
            source = if (imported.isNotEmpty()) "Imported Anki audio folder" else "No Anki source",
            error = liveResult.exceptionOrNull()?.message,
        )
    }

    private suspend fun findImportedAnkiRoot(): DocumentFile? =
        getFolders().firstNotNullOfOrNull { folder ->
            DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString))?.let(::findAnkiAudioRoot)
        }

    private fun findAnkiAudioRoot(root: DocumentFile): DocumentFile? {
        if (root.findFile("manifest.json")?.isFile == true) return root
        return root.findFile("anki_audio")?.takeIf { it.isDirectory && it.findFile("manifest.json")?.isFile == true }
    }

    private fun loadAnkiAudioCardsFromDocumentRoot(root: DocumentFile): List<AnkiAudioCard> =
        runCatching {
            val manifest = root.findFile("manifest.json") ?: return@runCatching emptyList()
            val raw = context.contentResolver.openInputStream(manifest.uri)?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching emptyList()
            val filesByName = root.listFiles().filter { it.isFile }.associateBy { it.name.orEmpty() }
            parseAnkiAudioManifest(raw) { audioFile ->
                filesByName[audioFile]?.uri?.toString()
            }.map {
                it.copy(origin = "Imported Anki audio folder")
            }
        }.getOrDefault(emptyList())

    private fun parseAnkiAudioManifest(
        raw: String,
        resolveAudioUri: (String) -> String?,
    ): List<AnkiAudioCard> {
        val cards = JSONObject(raw).getJSONArray("cards")
        return buildList {
            for (index in 0 until cards.length()) {
                val item = cards.getJSONObject(index)
                val audioFile = item.optString("audioFile")
                val audioUri = resolveAudioUri(audioFile).orEmpty()
                if (audioUri.isBlank()) continue
                add(
                    AnkiAudioCard(
                        cardId = item.optLong("cardId"),
                        noteId = item.optLong("noteId"),
                        deckName = item.optString("deckName", "Anki"),
                        category = item.optString("category", item.optString("deckName", "Anki")),
                        title = item.optString("title", "Anki card"),
                        answerText = item.optString("answerText"),
                        spokenText = item.optString("spokenText"),
                        isDue = item.optBoolean("isDue"),
                        audioFile = audioFile,
                        ankiMediaFile = audioFile.takeIf { it.isNotBlank() }?.let { "podder-$it" }.orEmpty(),
                        audioUriString = audioUri,
                        origin = "",
                    )
                )
            }
        }
    }

    private fun loadAnkiDroidAudioCards(importedFiles: Map<String, String>): List<AnkiAudioCard> {
        if (!isPackageInstalled(AnkiDroidContract.packageName)) {
            return emptyList()
        }

        val deckNames = loadAnkiDroidDeckNames()
        val allCards = queryAnkiDroidCards("deck:*", deckNames, importedFiles).associateBy { it.cardId }
        if (allCards.isEmpty()) return emptyList()
        val dueIds = queryAnkiDroidCards("is:due", deckNames, importedFiles).mapTo(mutableSetOf()) { it.cardId }
        return allCards.values.map { card ->
            card.copy(isDue = card.cardId in dueIds)
        }
    }

    private fun loadAnkiDroidDeckNames(): Map<Long, String> {
        val projection = arrayOf(AnkiDroidContract.Deck.deckId, AnkiDroidContract.Deck.deckName)
        return context.contentResolver.query(AnkiDroidContract.decksUri, projection, null, null, null)
            ?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getLong(AnkiDroidContract.Deck.deckId),
                            cursor.getStringOrBlank(AnkiDroidContract.Deck.deckName),
                        )
                    }
                }
            }
            .orEmpty()
    }

    private fun queryAnkiDroidCards(
        query: String,
        deckNames: Map<Long, String>,
        importedFiles: Map<String, String>,
    ): List<AnkiAudioCard> {
        val projection = arrayOf(
            AnkiDroidContract.Card.id,
            AnkiDroidContract.Card.noteId,
            AnkiDroidContract.Card.deckId,
            AnkiDroidContract.Card.questionSimple,
            AnkiDroidContract.Card.answerPure,
        )
        return context.contentResolver.query(AnkiDroidContract.cardsUri, projection, query, null, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val answer = cursor.getStringOrBlank(AnkiDroidContract.Card.answerPure)
                        val ankiMediaFile = extractPodderMediaFile(answer).orEmpty()
                        val audioFile = ankiMediaFile.removePrefix("podder-")
                        // Exported clip (AnkiDroid media or imported folder) if present, otherwise on-device speech.
                        val audioUri = ankiMediaFile.takeIf { it.isNotBlank() }?.let { media ->
                            importedFiles[audioFile] ?: resolveAnkiDroidMediaUri(media)
                        }.orEmpty()
                        val deckName = deckNames[cursor.getLong(AnkiDroidContract.Card.deckId)] ?: "Anki"
                        val title = htmlToPlainText(cursor.getStringOrBlank(AnkiDroidContract.Card.questionSimple))
                            .ifBlank { audioFile.substringBeforeLast('.').ifBlank { "Anki card" } }
                        val answerText = htmlToPlainText(answer)
                        if (title.isBlank() && answerText.isBlank()) continue
                        add(
                            AnkiAudioCard(
                                cardId = cursor.getLong(AnkiDroidContract.Card.id),
                                noteId = cursor.getLong(AnkiDroidContract.Card.noteId),
                                deckName = deckName,
                                category = deckName.substringBefore("::").ifBlank { deckName },
                                title = title,
                                answerText = answerText,
                                spokenText = spokenTextFor(deckName, title, answerText),
                                isDue = false,
                                audioFile = audioFile,
                                ankiMediaFile = ankiMediaFile,
                                audioUriString = audioUri,
                                origin = if (audioUri.isBlank()) "Phone voice" else "Exported audio",
                            )
                        )
                    }
                }
            }
            .orEmpty()
    }

    private fun resolveAnkiDroidMediaUri(fileName: String): String? =
        resolveIndexedAnkiDroidMediaUri(fileName)
            ?: resolveLegacyAnkiDroidMediaFile(fileName)

    private fun resolveIndexedAnkiDroidMediaUri(fileName: String): String? {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        return context.contentResolver.query(uri, projection, selection, arrayOf(fileName), null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                var fallback: String? = null
                while (cursor.moveToNext()) {
                    val contentUri = ContentUris.withAppendedId(uri, cursor.getLong(idIndex)).toString()
                    val relativePath = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                    if (relativePath.contains("AnkiDroid", ignoreCase = true) ||
                        relativePath.contains("collection.media", ignoreCase = true)
                    ) {
                        return@use contentUri
                    }
                    fallback = fallback ?: contentUri
                }
                fallback
            }
    }

    private fun resolveLegacyAnkiDroidMediaFile(fileName: String): String? {
        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            File("/sdcard"),
        ).distinctBy { it.absolutePath }
        val candidates = roots.flatMap { root ->
            listOf(
                File(root, "AnkiDroid/collection.media/$fileName"),
                File(root, "Android/data/${AnkiDroidContract.packageName}/files/AnkiDroid/collection.media/$fileName"),
            )
        }
        return candidates.firstOrNull { it.isFile && it.canRead() }?.let { Uri.fromFile(it).toString() }
    }

    private fun scanYoutubeFolder(): List<MediaEntry> {
        val root = youtubeDirectory()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") && MediaSupport.isPlayableExtension(it.extension) }
            .map { file ->
                val artwork = File(file.parentFile, file.nameWithoutExtension + ".jpg")
                MediaEntry(
                    uriString = Uri.fromFile(file).toString(),
                    title = file.nameWithoutExtension.ifBlank { "Untitled" },
                    folderName = youtubeFolderName,
                    durationMs = mediaDuration(file),
                    isVideo = MediaSupport.isVideoUri(file.name),
                    mimeType = MediaSupport.inferMimeType(file.name),
                    deletable = true,
                    artworkUri = artwork.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() },
                )
            }
            .toList()
    }

    private fun youtubeDirectory(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, youtubeFolderName)

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess

    private fun mediaDuration(file: File): Long {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun scanIndexedMedia(folder: MediaFolder): List<MediaEntry>? {
        val relativePrefix = mediaRelativePath(folder) ?: return null
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = buildString {
            append("(")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=? OR ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE)
            append("=?) AND ")
            append(MediaStore.MediaColumns.RELATIVE_PATH)
            append(" LIKE ?")
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            if (relativePrefix.isBlank()) "%" else "$relativePrefix%",
        )
        val sortOrder =
            "${MediaStore.MediaColumns.RELATIVE_PATH} ASC, ${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"

        val results = mutableListOf<MediaEntry>()
        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex).orEmpty()
                val mimeType = cursor.getString(mimeIndex)
                val mediaType = cursor.getInt(typeIndex)
                val contentUri = ContentUris.withAppendedId(uri, id)
                results += MediaEntry(
                    uriString = contentUri.toString(),
                    title = displayName.substringBeforeLast('.').ifBlank { "Untitled" },
                    folderName = folder.name,
                    durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L),
                    isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                        mimeType?.startsWith("video/") == true,
                    mimeType = mimeType,
                )
            }
        }
        return results
    }

    private fun scanFolder(root: DocumentFile, folderName: String): List<MediaEntry> {
        val stack = ArrayDeque<DocumentFile>()
        val results = mutableListOf<MediaEntry>()
        stack += root
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            current.listFiles().forEach { file ->
                when {
                    file.isDirectory -> stack += file
                    file.isFile -> {
                        val ext = file.name?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
                        val isPlayable = MediaSupport.isPlayableExtension(ext)
                        if (isPlayable) {
                            // In scanFolder() inside PlayerRepository
                            results += MediaEntry(
                                uriString = file.uri.toString(),
                                title = file.name?.substringBeforeLast('.') ?: "Untitled",
                                folderName = folderName,
                                durationMs = 0L, // Leave this at 0. ExoPlayer will provide the real duration upon playback.
                                isVideo = MediaSupport.isVideoUri(file.uri.toString()),
                                mimeType = file.type,
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    private fun mediaRelativePath(folder: MediaFolder): String? {
        val uri = Uri.parse(folder.uriString)
        if (uri.authority != "com.android.externalstorage.documents") return null
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val relative = treeId.substringAfter(':', "").trim('/')
        return if (relative.isBlank()) "" else "$relative/"
    }

    private fun encodeFolder(folder: MediaFolder): String = folder.uriString + separator + folder.name
}

private fun Cursor.getStringOrBlank(column: String): String =
    getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
        if (isNull(index)) "" else getString(index).orEmpty()
    }.orEmpty()

private fun Cursor.getLong(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
        if (isNull(index)) 0L else getLong(index)
    } ?: 0L

private fun extractPodderMediaFile(answerHtml: String): String? {
    val match = Regex("""\[sound:(podder-[^\]]+)]""").find(answerHtml) ?: return null
    return match.groupValues[1]
}

internal fun isPackageVisible(context: Context, packageName: String): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

private fun htmlToPlainText(value: String): String =
    value
        .replace(Regex("""\[sound:[^\]]*]"""), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Mirrors the desktop exporter's phrasing so phone-voiced cards sound like the exported ones. */
internal fun spokenTextFor(deckName: String, question: String, answer: String): String =
    if (deckName.contains("japa", ignoreCase = true) && question.isNotBlank() && answer.isNotBlank()) {
        "The meaning of $question is $answer."
    } else {
        listOf(question, answer).filter { it.isNotBlank() }.joinToString(". ... ")
    }
