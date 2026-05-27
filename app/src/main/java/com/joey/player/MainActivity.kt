package com.joey.player

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.joey.player.domain.MediaSupport
import com.joey.player.ui.theme.SlatePlayerTheme
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

object PlayerKeys {
    const val amoled = "amoled"
    const val folders = "folders"
    const val lastUri = "last_uri"
    const val lastTitle = "last_title"
    const val lastPosition = "last_position"
    const val lastDuration = "last_duration"
    const val lastIsVideo = "last_is_video"
    const val playbackSpeed = "playback_speed"
    const val shuffleEnabled = "shuffle_enabled"
    const val repeatMode = "repeat_mode"
}

data class MediaFolder(
    val uriString: String,
    val name: String,
)

data class MediaEntry(
    val uriString: String,
    val title: String,
    val folderName: String,
    val durationMs: Long,
    val isVideo: Boolean,
    val mimeType: String? = null,
    val deletable: Boolean = false,
)

data class AnkiAudioCard(
    val cardId: Long,
    val noteId: Long,
    val deckName: String,
    val category: String,
    val title: String,
    val answerText: String,
    val spokenText: String,
    val isDue: Boolean,
    val audioFile: String = "",
    val ankiMediaFile: String = "",
    val audioUriString: String = "",
    val origin: String = "Bundled export",
) {
    fun asMediaEntry(): MediaEntry =
        MediaEntry(
            uriString = audioUriString,
            title = title,
            folderName = deckName,
            durationMs = 0L,
            isVideo = false,
            mimeType = MediaSupport.inferMimeType(audioUriString),
        )
}

data class AnkiAudioLibrary(
    val cards: List<AnkiAudioCard>,
    val source: String,
    val error: String? = null,
)

data class ResumeState(
    val uriString: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isVideo: Boolean,
)

data class PlayerState(
    val currentUri: String? = null,
    val currentTitle: String = "",
    val isPlaying: Boolean = false,
    val isVideo: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class PlaybackPrefs(
    val speed: Float = 1f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class PlayerUiState(
    val amoled: Boolean = false,
    val folders: List<MediaFolder> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
    val ankiCards: List<AnkiAudioCard> = emptyList(),
    val ankiSource: String = "Bundled export",
    val ankiError: String? = null,
    val query: String = "",
    val scanning: Boolean = false,
    val youtubeDownloading: Boolean = false,
    val youtubeProgress: Float? = null,
    val youtubeStatus: String? = null,
    val resumeState: ResumeState? = null,
    val playerState: PlayerState = PlayerState(),
)

data class FolderMediaGroup(
    val folder: MediaFolder,
    val items: List<MediaEntry>,
)

private data class YoutubeUiInputs(
    val filteredMedia: Pair<String, List<MediaEntry>>,
    val scanning: Boolean,
    val playerState: PlayerState,
    val youtube: Triple<Boolean, Float?, String?>,
)

private data class AnkiUiInputs(
    val amoled: Boolean,
    val resume: ResumeState?,
    val folders: List<MediaFolder>,
    val ankiLibrary: AnkiAudioLibrary,
)

private object AnkiDroidContract {
    const val packageName = "com.ichi2.anki"
    const val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val authority = "com.ichi2.anki.flashcards"
    val cardsUri: Uri = Uri.parse("content://$authority/cards")
    val decksUri: Uri = Uri.parse("content://$authority/decks")

    object Card {
        const val id = "_id"
        const val noteId = "note_id"
        const val deckId = "deck_id"
        const val questionSimple = "question_simple"
        const val answerPure = "answer_pure"
    }

    object Deck {
        const val deckId = "deck_id"
        const val deckName = "deck_name"
    }
}

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
            "file" -> uri.path?.let { File(it).delete() } == true
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            else -> false
        }
    }

    suspend fun downloadYoutube(url: String, onProgress: (Float?, String) -> Unit): File = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        require(cleanUrl.isNotBlank()) { "Paste a YouTube URL first." }

        val outputDir = youtubeDirectory().apply { mkdirs() }
        onProgress(null, "Preparing yt-dlp...")
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)

        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            addOption("--restrict-filenames")
            addOption("-f", "bv*+ba/best")
            addOption("--merge-output-format", "mp4")
            addOption("-o", File(outputDir, "%(title).180B-%(id)s.%(ext)s").absolutePath)
        }

        YoutubeDL.getInstance().execute(request, null, false) { progress, _, line ->
            val normalizedProgress = progress.takeIf { it >= 0f }?.let { (it / 100f).coerceIn(0f, 1f) }
            onProgress(normalizedProgress, line.ifBlank { "Downloading..." })
        }

        scanYoutubeFolder().maxByOrNull { File(Uri.parse(it.uriString).path.orEmpty()).lastModified() }
            ?.let { File(Uri.parse(it.uriString).path.orEmpty()) }
            ?.takeIf { it.isFile }
            ?: outputDir.listFiles()
                ?.filter { it.isFile && MediaSupport.isPlayableExtension(it.extension) }
                ?.maxByOrNull { it.lastModified() }
            ?: error("Download finished but no playable file was found.")
    }

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
        val liveResult = runCatching { loadAnkiDroidAudioCards() }
        liveResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { liveCards ->
            return@withContext AnkiAudioLibrary(
                cards = liveCards.sortedWith(compareBy(AnkiAudioCard::category, AnkiAudioCard::title)),
                source = "AnkiDroid synced collection",
            )
        }
        val imported = loadImportedAnkiAudioCards()
        if (imported.isNotEmpty()) {
            return@withContext AnkiAudioLibrary(
                cards = imported.sortedWith(compareBy(AnkiAudioCard::category, AnkiAudioCard::title)),
                source = "Imported Anki audio folder",
                error = liveResult.exceptionOrNull()?.message,
            )
        }
        val bundled = loadBundledAnkiAudioCards()
        AnkiAudioLibrary(
            cards = bundled,
            source = "Bundled export",
            error = liveResult.exceptionOrNull()?.message,
        )
    }

    private suspend fun loadImportedAnkiAudioCards(): List<AnkiAudioCard> {
        val folders = getFolders()
        return folders.firstNotNullOfOrNull { folder ->
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString)) ?: return@firstNotNullOfOrNull null
            val audioRoot = findAnkiAudioRoot(root) ?: return@firstNotNullOfOrNull null
            loadAnkiAudioCardsFromDocumentRoot(audioRoot)
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
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

    private fun loadBundledAnkiAudioCards(): List<AnkiAudioCard> =
        runCatching {
            val raw = context.assets.open("anki_audio/manifest.json").bufferedReader().use { it.readText() }
            parseAnkiAudioManifest(raw, ::bundledAssetUri).map { it.copy(origin = "Bundled export") }
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

    private fun loadAnkiDroidAudioCards(): List<AnkiAudioCard> {
        if (!isPackageInstalled(AnkiDroidContract.packageName)) {
            return emptyList()
        }

        val deckNames = loadAnkiDroidDeckNames()
        val allCards = queryAnkiDroidCards("deck:*", deckNames).associateBy { it.cardId }
        if (allCards.isEmpty()) return emptyList()
        val dueIds = queryAnkiDroidCards("is:due", deckNames).mapTo(mutableSetOf()) { it.cardId }
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
                        val ankiMediaFile = extractPodderMediaFile(answer) ?: continue
                        val audioFile = ankiMediaFile.removePrefix("podder-")
                        val audioUri = resolveAnkiDroidMediaUri(ankiMediaFile) ?: bundledAssetUri(audioFile) ?: continue
                        val deckName = deckNames[cursor.getLong(AnkiDroidContract.Card.deckId)] ?: "Anki"
                        val title = htmlToPlainText(cursor.getStringOrBlank(AnkiDroidContract.Card.questionSimple))
                            .ifBlank { audioFile.substringBeforeLast('.').ifBlank { "Anki card" } }
                        val answerText = htmlToPlainText(answer)
                        add(
                            AnkiAudioCard(
                                cardId = cursor.getLong(AnkiDroidContract.Card.id),
                                noteId = cursor.getLong(AnkiDroidContract.Card.noteId),
                                deckName = deckName,
                                category = deckName.substringBefore("::").ifBlank { deckName },
                                title = title,
                                answerText = answerText,
                                spokenText = answerText,
                                isDue = false,
                                audioFile = audioFile,
                                ankiMediaFile = ankiMediaFile,
                                audioUriString = audioUri,
                                origin = "AnkiDroid synced collection",
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

    private fun bundledAssetUri(fileName: String): String? =
        fileName.takeIf { it.isNotBlank() && bundledAssetExists(it) }?.let { "asset:///anki_audio/$it" }

    private fun bundledAssetExists(fileName: String): Boolean =
        runCatching {
            context.assets.open("anki_audio/$fileName").use { true }
        }.getOrDefault(false)

    private fun scanYoutubeFolder(): List<MediaEntry> {
        val root = youtubeDirectory()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && MediaSupport.isPlayableExtension(it.extension) }
            .map { file ->
                MediaEntry(
                    uriString = Uri.fromFile(file).toString(),
                    title = file.nameWithoutExtension.ifBlank { "Untitled" },
                    folderName = youtubeFolderName,
                    durationMs = mediaDuration(file),
                    isVideo = MediaSupport.isVideoUri(file.name),
                    mimeType = MediaSupport.inferMimeType(file.name),
                    deletable = true,
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

private fun isPackageVisible(context: Context, packageName: String): Boolean =
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

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PlayerRepository(application)
    private var playerListener: Player.Listener? = null
    private var progressJob: Job? = null
    private val allMedia = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    private val ankiCards = MutableStateFlow(AnkiAudioLibrary(emptyList(), "Bundled export"))
    private val query = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)
    private val youtubeDownloading = MutableStateFlow(false)
    private val youtubeProgress = MutableStateFlow<Float?>(null)
    private val youtubeStatus = MutableStateFlow<String?>(null)
    private val playerState = MutableStateFlow(PlayerState())
    var controller by mutableStateOf<ExoPlayer?>(null)
        private set

    private val filteredMedia = combine(allMedia, query) { mediaValue, queryValue ->
        queryValue to if (queryValue.isBlank()) {
            mediaValue
        } else {
            mediaValue.filter {
                it.title.contains(queryValue, ignoreCase = true) ||
                    it.folderName.contains(queryValue, ignoreCase = true)
            }
        }
    }

    private val uiInputs = combine(repository.amoledFlow, repository.resumeFlow, folders, ankiCards) { amoled, resume, foldersValue, ankiLibrary ->
        AnkiUiInputs(amoled, resume, foldersValue, ankiLibrary)
    }

    private val youtubeInputs = combine(youtubeDownloading, youtubeProgress, youtubeStatus) { downloading, progress, status ->
        Triple(downloading, progress, status)
    }

    private val playbackInputs = combine(filteredMedia, scanning, playerState, youtubeInputs) { filteredMediaValue, scanningValue, playerValue, youtubeValue ->
        YoutubeUiInputs(filteredMediaValue, scanningValue, playerValue, youtubeValue)
    }

    val uiState = combine(uiInputs, playbackInputs) { base, playback ->
        val (amoled, resume, foldersValue, ankiLibrary) = base
        val (filteredMediaValue, scanningValue, playerValue, youtubeValue) = playback
        val (queryValue, filtered) = filteredMediaValue
        val (downloading, progress, status) = youtubeValue
        PlayerUiState(
            amoled = amoled,
            folders = foldersValue,
            media = filtered,
            ankiCards = ankiLibrary.cards,
            ankiSource = ankiLibrary.source,
            ankiError = ankiLibrary.error,
            query = queryValue,
            scanning = scanningValue,
            youtubeDownloading = downloading,
            youtubeProgress = progress,
            youtubeStatus = status,
            resumeState = resume?.takeIf { saved ->
                allMedia.value.any { it.uriString == saved.uriString } &&
                    saved.positionMs > 5_000L &&
                    playerValue.currentUri != saved.uriString
            },
            playerState = playerValue,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    init {
        loadFolders()
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleTheme() {
        viewModelScope.launch {
            repository.setAmoled(!uiState.value.amoled)
        }
    }

    fun addFolder(uri: Uri, name: String) {
        viewModelScope.launch {
            repository.addFolder(uri, name)
            loadFolders()
        }
    }

    fun removeFolder(folder: MediaFolder) {
        viewModelScope.launch {
            repository.removeFolder(folder)
            loadFolders()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            scanning.value = true
            allMedia.value = repository.scanMedia(folders.value)
            ankiCards.value = repository.loadAnkiAudioCards()
            scanning.value = false
        }
    }

    fun refreshAnkiAudio() {
        viewModelScope.launch {
            ankiCards.value = repository.loadAnkiAudioCards()
        }
    }

    fun deleteMedia(entry: MediaEntry) {
        viewModelScope.launch {
            if (repository.deleteMedia(entry)) {
                if (playerState.value.currentUri == entry.uriString) {
                    controller?.stop()
                    repository.clearResume()
                }
                rescan()
            }
        }
    }

    fun downloadYoutube(url: String) {
        if (youtubeDownloading.value) return
        viewModelScope.launch {
            youtubeDownloading.value = true
            youtubeProgress.value = null
            youtubeStatus.value = "Starting download..."
            runCatching {
                repository.downloadYoutube(url) { progress, status ->
                    youtubeProgress.value = progress
                    youtubeStatus.value = status.ifBlank { "Downloading..." }
                }
            }.onSuccess { file ->
                youtubeProgress.value = 1f
                youtubeStatus.value = "Saved ${file.name}"
                rescan()
            }.onFailure { error ->
                youtubeProgress.value = null
                youtubeStatus.value = error.message ?: "Download failed."
            }
            youtubeDownloading.value = false
        }
    }

    fun playFromVisibleList(entry: MediaEntry) {
        val currentList = uiState.value.media
        ensureController { target ->
            playList(currentList, currentList.indexOfFirst { it.uriString == entry.uriString }, 0L, target)
        }
    }

    fun playAnkiCards(cards: List<AnkiAudioCard>, startCard: AnkiAudioCard? = null) {
        val playable = cards.mapNotNull { card ->
            card.asMediaEntry().takeIf { it.uriString.isNotBlank() }
        }
        if (playable.isEmpty()) return
        val startIndex = startCard?.let { selected ->
            playable.indexOfFirst { it.uriString == selected.audioUriString }
        }?.coerceAtLeast(0) ?: 0
        ensureController { target ->
            playList(playable, startIndex, 0L, target)
        }
    }

    fun resumeSaved() {
        val resume = uiState.value.resumeState ?: return
        val list = uiState.value.media.ifEmpty { allMedia.value }
        val index = list.indexOfFirst { it.uriString == resume.uriString }
        if (index >= 0) {
            ensureController { target ->
                playList(list, index, resume.positionMs, target)
            }
        }
    }

    fun dismissResumePrompt() {
        viewModelScope.launch {
            repository.clearResume()
        }
    }

    fun togglePlayPause() {
        ensureController { target ->
            if (target.isPlaying) target.pause() else target.play()
            refreshPlayerState()
        }
    }

    fun skipNext() {
        controller?.let {
            it.seekToNext()
            refreshPlayerState()
        }
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
        refreshPlayerState()
    }

    fun seekTo(value: Float) {
        controller?.seekTo(value.toLong())
        refreshPlayerState()
    }

    fun jumpBy(deltaMs: Long) {
        controller?.let {
            val duration = it.duration.takeIf { length -> length > 0L } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, duration))
            refreshPlayerState()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        ensureController { target ->
            target.playbackParameters = PlaybackParameters(speed)
            persistPlaybackPrefs()
            refreshPlayerState()
        }
    }

    fun toggleShuffle() {
        ensureController { target ->
            target.shuffleModeEnabled = !target.shuffleModeEnabled
            persistPlaybackPrefs()
            refreshPlayerState()
        }
    }

    fun cycleRepeatMode() {
        ensureController { target ->
            target.repeatMode = when (target.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            persistPlaybackPrefs()
            refreshPlayerState()
        }
    }

    private fun playList(list: List<MediaEntry>, index: Int, seekMs: Long, target: ExoPlayer) {
        if (index !in list.indices) return

        val items = list.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.uriString)
                .setUri(entry.uriString)
                .setMimeType(entry.mimeType ?: MediaSupport.inferMimeType(entry.uriString))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.folderName)
                        .build(),
                )
                .build()
        }
        
        target.setMediaItems(items, index, seekMs)
        target.prepare()
        target.play()
        refreshPlayerState()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            folders.value = repository.getFolders()
            rescan()
        }
    }

    private fun ensureController(onReady: (ExoPlayer) -> Unit) {
        controller?.let { existing ->
            onReady(existing)
            return
        }

        val target = ExoPlayer.Builder(appContext)
            .build()
            .apply {
                setHandleAudioBecomingNoisy(true)
                attachListener(this)
            }
        controller = target
        startProgressUpdates()
        applyPlaybackPrefs(target)
        onReady(target)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val target = controller
                if (target != null && (target.isPlaying || target.playbackState == Player.STATE_BUFFERING)) {
                    refreshPlayerState()
                    kotlinx.coroutines.delay(500L)
                } else {
                    kotlinx.coroutines.delay(1_000L)
                }
            }
        }
    }

    private fun attachListener(target: ExoPlayer) {
        playerListener?.let(target::removeListener)
        playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshPlayerState()
                persistResumeState(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    viewModelScope.launch {
                        repository.clearResume()
                    }
                }
            }
        }.also(target::addListener)
    }

    private fun refreshPlayerState() {
        val target = controller ?: return
        val item = target.currentMediaItem
        playerState.value = PlayerState(
            currentUri = item?.mediaId,
            currentTitle = item?.mediaMetadata?.title?.toString().orEmpty(),
            isPlaying = target.isPlaying,
            isVideo = item?.localConfiguration?.mimeType?.startsWith("video/") == true,
            positionMs = target.currentPosition.coerceAtLeast(0L),
            durationMs = target.duration.takeIf { it > 0 } ?: 0L,
            playbackSpeed = target.playbackParameters.speed,
            hasNext = target.hasNextMediaItem(),
            hasPrevious = target.hasPreviousMediaItem(),
            shuffleEnabled = target.shuffleModeEnabled,
            repeatMode = target.repeatMode,
        )
    }

    private fun applyPlaybackPrefs(target: ExoPlayer) {
        viewModelScope.launch {
            val prefs = repository.getPlaybackPrefs()
            target.playbackParameters = PlaybackParameters(prefs.speed)
            target.shuffleModeEnabled = prefs.shuffleEnabled
            target.repeatMode = prefs.repeatMode
            refreshPlayerState()
        }
    }

    private fun persistResumeState(target: Player) {
        val item = target.currentMediaItem ?: return
        val duration = target.duration.takeIf { it > 0L } ?: 0L
        val position = target.currentPosition.coerceAtLeast(0L)
        viewModelScope.launch {
            if (duration > 0L && position >= duration - 1_500L) {
                repository.clearResume()
            } else {
                repository.saveResume(
                    ResumeState(
                        uriString = item.mediaId,
                        title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "Last media" },
                        positionMs = position,
                        durationMs = duration,
                        isVideo = item.localConfiguration?.mimeType?.startsWith("video/") == true,
                    ),
                )
            }
        }
    }

    private fun persistPlaybackPrefs() {
        val target = controller ?: return
        viewModelScope.launch {
            repository.savePlaybackPrefs(
                PlaybackPrefs(
                    speed = target.playbackParameters.speed,
                    shuffleEnabled = target.shuffleModeEnabled,
                    repeatMode = target.repeatMode,
                ),
            )
        }
    }

    override fun onCleared() {
        progressJob?.cancel()
        playerListener?.let { listener -> controller?.removeListener(listener) }
        controller?.release()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PlayerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
                onResult = {
                    viewModel.rescan()
                },
            )
            LaunchedEffect(Unit) {
                val neededPermissions = buildList {
                    if (Build.VERSION.SDK_INT >= 33) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                        add(Manifest.permission.READ_MEDIA_AUDIO)
                        add(Manifest.permission.READ_MEDIA_VIDEO)
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    if (isPackageVisible(context, AnkiDroidContract.packageName)) {
                        add(AnkiDroidContract.permission)
                    }
                }.filter { permission ->
                    ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
                }
                if (neededPermissions.isNotEmpty()) {
                    permissionLauncher.launch(neededPermissions.toTypedArray())
                }
            }
            SlatePlayerTheme(amoled = uiState.amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(
                        state = uiState,
                        onQueryChange = viewModel::setQuery,
                        onRefresh = viewModel::rescan,
                        onPlay = viewModel::playFromVisibleList,
                        onPlayAnkiCards = viewModel::playAnkiCards,
                        onRemoveFolder = viewModel::removeFolder,
                        onDownloadYoutube = viewModel::downloadYoutube,
                        onDeleteMedia = viewModel::deleteMedia,
                        onResume = viewModel::resumeSaved,
                        onDismissResume = viewModel::dismissResumePrompt,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onJumpBy = viewModel::jumpBy,
                        onSkipNext = viewModel::skipNext,
                        onSkipPrevious = viewModel::skipPrevious,
                        onSpeedChange = viewModel::setPlaybackSpeed,
                        onToggleShuffle = viewModel::toggleShuffle,
                        onCycleRepeatMode = viewModel::cycleRepeatMode,
                        onFolderPicked = viewModel::addFolder,
                    )
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerScreen(
    state: PlayerUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onPlayAnkiCards: (List<AnkiAudioCard>, AnkiAudioCard?) -> Unit,
    onRemoveFolder: (MediaFolder) -> Unit,
    onDownloadYoutube: (String) -> Unit,
    onDeleteMedia: (MediaEntry) -> Unit,
    onResume: () -> Unit,
    onDismissResume: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onJumpBy: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onFolderPicked: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    var inspectedMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var youtubeDialogOpen by rememberSaveable { mutableStateOf(false) }
    var youtubeUrl by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<MediaEntry?>(null) }
    var collapsedFolders by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showingAnki by rememberSaveable { mutableStateOf(false) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        val treeName = DocumentFile.fromTreeUri(context, uri)?.name
            ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
            .ifBlank { "Folder" }
        onFolderPicked(uri, treeName)
    }

    if (youtubeDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!state.youtubeDownloading) youtubeDialogOpen = false },
            title = { Text("Open YouTube video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = youtubeUrl,
                        onValueChange = { youtubeUrl = it },
                        singleLine = true,
                        enabled = !state.youtubeDownloading,
                        label = { Text("Paste YouTube link") },
                    )
                    Text(
                        "Downloads into Podder's YouTube folder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.youtubeStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.youtubeProgress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = youtubeUrl.isNotBlank() && !state.youtubeDownloading,
                    onClick = { onDownloadYoutube(youtubeUrl) },
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.youtubeDownloading,
                    onClick = { youtubeDialogOpen = false },
                ) {
                    Text("Close")
                }
            },
        )
    }

    deleteCandidate?.let { media ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete video?") },
            text = { Text("Remove \"${media.title}\" from the YouTube folder.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMedia(media)
                    deleteCandidate = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.resumeState != null) {
        AlertDialog(
            onDismissRequest = onDismissResume,
            title = { Text("Resume last session?") },
            text = {
                Text(
                    "${state.resumeState.title} at ${formatTime(state.resumeState.positionMs)}",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onResume()
                    onDismissResume()
                }) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResume) {
                    Text("Not now")
                }
            },
        )
    }

    inspectedMedia?.let { media ->
        AlertDialog(
            onDismissRequest = { inspectedMedia = null },
            title = { Text(media.title) },
            text = {
                Text(
                    buildString {
                        append(if (media.isVideo) "Video" else "Audio")
                        append("\n")
                        append("Folder: ")
                        append(media.folderName)
                        append("\n")
                        append("Duration: ")
                        append(formatTime(media.durationMs))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { inspectedMedia = null }) {
                    Text("Close")
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("podder", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (showingAnki) "anki audio" else "local audio + video",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                    }
                },
                actions = {
                    IconButton(onClick = { showingAnki = !showingAnki }) {
                        Icon(Icons.Rounded.School, contentDescription = if (showingAnki) "Show local library" else "Show Anki audio")
                    }
                    IconButton(onClick = { youtubeDialogOpen = true }) {
                        Icon(Icons.Rounded.SmartDisplay, contentDescription = "Download YouTube video")
                    }
                },
            )
        },
        bottomBar = {
            if (state.playerState.currentUri != null) {
                MiniPlayerBar(
                    playerState = state.playerState,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeek = onSeek,
                    onJumpBy = onJumpBy,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSpeedChange = onSpeedChange,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeatMode = onCycleRepeatMode,
                )
            }
        },
        floatingActionButton = {
            FilledIconButton(onClick = { folderLauncher.launch(null) }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add folder")
            }
        },
    ) { padding ->
        val folderGroups = remember(state.folders, state.media) {
            val persistedGroups = state.folders.map { folder ->
                FolderMediaGroup(
                    folder = folder,
                    items = state.media.filter { it.folderName == folder.name },
                )
            }
            val knownFolderNames = state.folders.mapTo(mutableSetOf()) { it.name }
            val virtualGroups = state.media
                .filter { it.folderName !in knownFolderNames }
                .groupBy { it.folderName }
                .map { (name, items) ->
                    FolderMediaGroup(
                        folder = MediaFolder("virtual:$name", name),
                        items = items,
                    )
                }
            virtualGroups + persistedGroups
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .statusBarsPadding(),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text("Search tracks or folders") },
                    )
                }
            }
            if (showingAnki) {
                item {
                    AnkiAudioPanel(
                        cards = state.ankiCards,
                        source = state.ankiSource,
                        error = state.ankiError,
                        playerState = state.playerState,
                        onPlayAnkiCards = onPlayAnkiCards,
                    )
                }
            } else if (state.media.isEmpty()) {
                item {
                    EmptyLibraryCard(scanning = state.scanning)
                }
            } else {
                folderGroups.forEach { group ->
                    item(key = "folder-${group.folder.uriString}") {
                        FolderHeaderRow(
                            folder = group.folder,
                            fileCount = group.items.size,
                            collapsed = group.folder.uriString in collapsedFolders,
                            removable = !group.folder.uriString.startsWith("virtual:"),
                            onToggle = {
                                collapsedFolders = if (group.folder.uriString in collapsedFolders) {
                                    collapsedFolders - group.folder.uriString
                                } else {
                                    collapsedFolders + group.folder.uriString
                                }
                            },
                            onRemove = { onRemoveFolder(group.folder) },
                        )
                    }
                    if (group.folder.uriString !in collapsedFolders) {
                        itemsIndexed(group.items, key = { _, item -> item.uriString }) { _, item ->
                            MediaRow(
                                item = item,
                                active = state.playerState.currentUri == item.uriString,
                                onClick = { onPlay(item) },
                                onLongPress = { inspectedMedia = item },
                                onDelete = if (item.deletable) {
                                    { deleteCandidate = item }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(90.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnkiAudioPanel(
    cards: List<AnkiAudioCard>,
    source: String,
    error: String?,
    playerState: PlayerState,
    onPlayAnkiCards: (List<AnkiAudioCard>, AnkiAudioCard?) -> Unit,
) {
    var dueOnly by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(cards) { cards.map { it.category }.distinct().sorted() }
    val filteredCards = remember(cards, dueOnly, selectedCategory) {
        cards.filter { card ->
            (!dueOnly || card.isDue) && (selectedCategory == null || card.category == selectedCategory)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Anki audio", fontWeight = FontWeight.SemiBold)
                        Text(
                            "$source • ${filteredCards.size} selected • ${cards.count { it.isDue }} due today • ${cards.size} total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Due",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(checked = dueOnly, onCheckedChange = { dueOnly = it })
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { selectedCategory = null },
                        label = { Text("All") },
                        leadingIcon = if (selectedCategory == null) {
                            { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                        } else null,
                    )
                    categories.forEach { category ->
                        AssistChip(
                            onClick = { selectedCategory = category },
                            label = { Text(category) },
                            leadingIcon = if (selectedCategory == category) {
                                { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                            } else null,
                        )
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        enabled = filteredCards.isNotEmpty(),
                        onClick = { onPlayAnkiCards(filteredCards, null) },
                        label = { Text("Play selected") },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    )
                    if (selectedCategory != null) {
                        AssistChip(
                            enabled = filteredCards.isNotEmpty(),
                            onClick = { onPlayAnkiCards(filteredCards, null) },
                            label = { Text("Play category") },
                            leadingIcon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                        )
                    }
                }
            }
        }

        if (cards.isEmpty()) {
            EmptyAnkiCard()
        } else if (filteredCards.isEmpty()) {
            Text(
                "No cards match the current Anki audio filters.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            filteredCards.forEach { card ->
                AnkiAudioRow(
                    card = card,
                    active = playerState.currentUri == card.asMediaEntry().uriString,
                    onClick = { onPlayAnkiCards(filteredCards, card) },
                )
            }
        }
    }
}

@Composable
private fun EmptyAnkiCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No exported Anki audio", fontWeight = FontWeight.SemiBold)
            Text(
                "Add the exported anki_audio folder with the folder button, then refresh. AnkiDroid live access is optional.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnkiAudioRow(
    card: AnkiAudioCard,
    active: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (active) Icons.Rounded.PauseCircle else Icons.Rounded.School,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    card.answerText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${card.deckName} • ${if (card.isDue) "due today" else "not due"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.PlayCircle, contentDescription = "Play Anki card")
        }
    }
}

@Composable
private fun FolderHeaderRow(
    folder: MediaFolder,
    fileCount: Int,
    collapsed: Boolean,
    removable: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (collapsed) "+" else "-", style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "$fileCount files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (removable) {
                FilledIconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(26.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove folder", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(scanning: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        ),
                    ),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No media yet", fontWeight = FontWeight.SemiBold)
            Text(
                if (scanning) {
                    "Scanning your folders."
                } else {
                    "Add one or more folders to surface local MP3 and MP4 files. Playback continues in the background and the last position is saved automatically."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    item: MediaEntry,
    active: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    "${item.folderName} • ${formatTime(item.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete YouTube video",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Icon(
                        if (active) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (item.isVideo) "video" else "audio",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiniPlayerBar(
    playerState: PlayerState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onJumpBy: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(Color.Transparent),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(playerState.currentTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${formatTime(playerState.positionMs)} / ${formatTime(playerState.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(onClick = onTogglePlayPause, shape = CircleShape) {
                    Icon(
                        if (playerState.isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayArrow,
                        contentDescription = "Play or pause",
                    )
                }
            }
            Slider(
                value = playerState.positionMs.toFloat().coerceAtMost(playerState.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = onSeek,
                valueRange = 0f..playerState.durationMs.toFloat().coerceAtLeast(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(onClick = { onJumpBy(-15_000L) }, label = { Text("-15s") })
                AssistChip(onClick = { onJumpBy(-5_000L) }, label = { Text("-5s") })
                IconButton(onClick = onSkipPrevious, enabled = playerState.hasPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = onSkipNext, enabled = playerState.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                AssistChip(onClick = { onJumpBy(5_000L) }, label = { Text("+5s") })
                AssistChip(onClick = { onJumpBy(15_000L) }, label = { Text("+15s") })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onToggleShuffle,
                    label = { Text("Shuffle") },
                    leadingIcon = {
                        Icon(
                            if (playerState.shuffleEnabled) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle,
                            contentDescription = null,
                        )
                    },
                )
                AssistChip(
                    onClick = onCycleRepeatMode,
                    label = { Text(repeatModeLabel(playerState.repeatMode)) },
                    leadingIcon = {
                        Icon(
                            when (playerState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                                Player.REPEAT_MODE_ALL -> Icons.Rounded.RepeatOn
                                else -> Icons.Rounded.Repeat
                            },
                            contentDescription = null,
                        )
                    },
                )
                listOf(0.8f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    AssistChip(
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") },
                        leadingIcon = if (playerState.playbackSpeed == speed) {
                            { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
                        } else null,
                    )
                }
            }
        }
    }
}

private fun repeatModeLabel(repeatMode: Int): String = when (repeatMode) {
    Player.REPEAT_MODE_ONE -> "Repeat 1"
    Player.REPEAT_MODE_ALL -> "Repeat all"
    else -> "Repeat off"
}

private fun formatTime(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
