package com.joey.player

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.joey.player.domain.MediaSupport
import com.joey.player.playback.PlayerService
import com.joey.player.ui.theme.SlatePlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Context.playerStore: DataStore<Preferences> by preferencesDataStore(name = "slate_player")

object PlayerKeys {
    val amoled = booleanPreferencesKey("amoled")
    val folders = stringPreferencesKey("folders")
    val lastUri = stringPreferencesKey("last_uri")
    val lastTitle = stringPreferencesKey("last_title")
    val lastPosition = longPreferencesKey("last_position")
    val lastDuration = longPreferencesKey("last_duration")
    val lastIsVideo = booleanPreferencesKey("last_is_video")
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
)

data class PlayerUiState(
    val amoled: Boolean = false,
    val folders: List<MediaFolder> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
    val query: String = "",
    val scanning: Boolean = false,
    val resumeState: ResumeState? = null,
    val playerState: PlayerState = PlayerState(),
)

class PlayerRepository(
    private val context: Context,
) {
    private val separator = "\u0001"

    val amoledFlow: Flow<Boolean> = context.playerStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PlayerKeys.amoled] ?: false }

    val resumeFlow: Flow<ResumeState?> = context.playerStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val uri = prefs[PlayerKeys.lastUri] ?: return@map null
            ResumeState(
                uriString = uri,
                title = prefs[PlayerKeys.lastTitle] ?: "Last media",
                positionMs = prefs[PlayerKeys.lastPosition] ?: 0L,
                durationMs = prefs[PlayerKeys.lastDuration] ?: 0L,
                isVideo = prefs[PlayerKeys.lastIsVideo] ?: false,
            )
        }

    suspend fun getFolders(): List<MediaFolder> {
        val raw = context.playerStore.data.first()[PlayerKeys.folders].orEmpty()
        return raw.split("\n")
            .filter { it.isNotBlank() && it.contains(separator) }
            .map {
                val (uri, name) = it.split(separator, limit = 2)
                MediaFolder(uri, name)
            }
    }

    suspend fun addFolder(uri: Uri, name: String) {
        val encoded = encodeFolder(MediaFolder(uri.toString(), name))
        context.playerStore.edit { prefs ->
            val current = prefs[PlayerKeys.folders].orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }
                .toMutableSet()
            current.removeAll { it.startsWith(uri.toString() + separator) }
            current += encoded
            prefs[PlayerKeys.folders] = current.sorted().joinToString("\n")
        }
    }

    suspend fun removeFolder(folder: MediaFolder) {
        context.playerStore.edit { prefs ->
            val current = prefs[PlayerKeys.folders].orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith(folder.uriString + separator) }
            prefs[PlayerKeys.folders] = current.joinToString("\n")
        }
    }

    suspend fun setAmoled(enabled: Boolean) {
        context.playerStore.edit { it[PlayerKeys.amoled] = enabled }
    }

    suspend fun saveResume(resume: ResumeState) {
        context.playerStore.edit { prefs ->
            prefs[PlayerKeys.lastUri] = resume.uriString
            prefs[PlayerKeys.lastTitle] = resume.title
            prefs[PlayerKeys.lastPosition] = resume.positionMs
            prefs[PlayerKeys.lastDuration] = resume.durationMs
            prefs[PlayerKeys.lastIsVideo] = resume.isVideo
        }
    }

    suspend fun clearResume() {
        context.playerStore.edit { prefs ->
            prefs.remove(PlayerKeys.lastUri)
            prefs.remove(PlayerKeys.lastTitle)
            prefs.remove(PlayerKeys.lastPosition)
            prefs.remove(PlayerKeys.lastDuration)
            prefs.remove(PlayerKeys.lastIsVideo)
        }
    }

    suspend fun scanMedia(folders: List<MediaFolder>): List<MediaEntry> = withContext(Dispatchers.IO) {
        folders.flatMap { folder ->
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString)) ?: return@flatMap emptyList()
            scanFolder(root, folder.name)
        }.sortedWith(compareBy(MediaEntry::folderName, MediaEntry::title))
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
                            results += MediaEntry(
                                uriString = file.uri.toString(),
                                title = file.name?.substringBeforeLast('.') ?: "Untitled",
                                folderName = folderName,
                                durationMs = readDuration(file.uri),
                                isVideo = MediaSupport.isVideoUri(file.uri.toString()),
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    private fun readDuration(uri: Uri): Long {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun encodeFolder(folder: MediaFolder): String = folder.uriString + separator + folder.name
}

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = PlayerRepository(application)
    private val sessionToken = SessionToken(application, ComponentName(application, PlayerService::class.java))
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerListener: Player.Listener? = null
    private val allMedia = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    private val query = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)
    private val playerState = MutableStateFlow(PlayerState())
    var controller by mutableStateOf<MediaController?>(null)
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

    private val uiInputs = combine(repository.amoledFlow, repository.resumeFlow, folders) { amoled, resume, foldersValue ->
        Triple(amoled, resume, foldersValue)
    }

    private val playbackInputs = combine(filteredMedia, scanning, playerState) { filteredMediaValue, scanningValue, playerValue ->
        Triple(filteredMediaValue, scanningValue, playerValue)
    }

    val uiState = combine(uiInputs, playbackInputs) { base, playback ->
        val (amoled, resume, foldersValue) = base
        val (filteredMediaValue, scanningValue, playerValue) = playback
        val (queryValue, filtered) = filteredMediaValue
        PlayerUiState(
            amoled = amoled,
            folders = foldersValue,
            media = filtered,
            query = queryValue,
            scanning = scanningValue,
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
        connectController()
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
            scanning.value = false
        }
    }

    fun playFromVisibleList(entry: MediaEntry) {
        val currentList = uiState.value.media
        playList(currentList, currentList.indexOfFirst { it.uriString == entry.uriString }, 0L)
    }

    fun resumeSaved() {
        val resume = uiState.value.resumeState ?: return
        val list = uiState.value.media.ifEmpty { allMedia.value }
        val index = list.indexOfFirst { it.uriString == resume.uriString }
        if (index >= 0) {
            playList(list, index, resume.positionMs)
        }
    }

    fun dismissResumePrompt() {
        viewModelScope.launch {
            repository.clearResume()
        }
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
            refreshPlayerState()
        }
    }

    fun skipNext() {
        controller?.seekToNext()
        refreshPlayerState()
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
            it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0L))
            refreshPlayerState()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        refreshPlayerState()
    }

    private fun playList(list: List<MediaEntry>, index: Int, seekMs: Long) {
        val target = controller ?: return
        if (index !in list.indices) return
        val items = list.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.uriString)
                .setUri(entry.uriString)
                .setMimeType(MediaSupport.inferMimeType(entry.uriString))
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

    private fun connectController() {
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync().also { future ->
            future.addListener(
                {
                    val built = runCatching { future.get() }.getOrNull() ?: return@addListener
                    controller = built
                    attachListener(built)
                    refreshPlayerState()
                    viewModelScope.launch {
                        while (true) {
                            refreshPlayerState()
                            kotlinx.coroutines.delay(500L)
                        }
                    }
                },
                ContextCompat.getMainExecutor(getApplication()),
            )
        }
    }

    private fun attachListener(target: MediaController) {
        playerListener?.let(target::removeListener)
        playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshPlayerState()
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
            isVideo = item?.mediaId?.let(MediaSupport::isVideoUri) == true,
            positionMs = target.currentPosition.coerceAtLeast(0L),
            durationMs = target.duration.takeIf { it > 0 } ?: 0L,
            playbackSpeed = target.playbackParameters.speed,
            hasNext = target.hasNextMediaItem(),
            hasPrevious = target.hasPreviousMediaItem(),
        )
    }

    override fun onCleared() {
        playerListener?.let { listener -> controller?.removeListener(listener) }
        controller?.release()
        controllerFuture?.cancel(true)
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PlayerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {},
            )
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            SlatePlayerTheme(amoled = uiState.amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(
                        state = uiState,
                        controller = viewModel.controller,
                        onToggleTheme = viewModel::toggleTheme,
                        onQueryChange = viewModel::setQuery,
                        onRefresh = viewModel::rescan,
                        onPlay = viewModel::playFromVisibleList,
                        onRemoveFolder = viewModel::removeFolder,
                        onResume = viewModel::resumeSaved,
                        onDismissResume = viewModel::dismissResumePrompt,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onJumpBy = viewModel::jumpBy,
                        onSkipNext = viewModel::skipNext,
                        onSkipPrevious = viewModel::skipPrevious,
                        onSpeedChange = viewModel::setPlaybackSpeed,
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
    controller: MediaController?,
    onToggleTheme: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onRemoveFolder: (MediaFolder) -> Unit,
    onResume: () -> Unit,
    onDismissResume: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onJumpBy: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFolderPicked: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
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

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Podcasts", fontWeight = FontWeight.SemiBold)
                        Text(
                            "local audio + video",
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.amoled) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                            contentDescription = null,
                        )
                        Switch(checked = state.amoled, onCheckedChange = { onToggleTheme() })
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
                )
            }
        },
        floatingActionButton = {
            FilledIconButton(onClick = { folderLauncher.launch(null) }) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Add folder")
            }
        },
    ) { padding ->
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
                    if (controller != null && state.playerState.isVideo && !LocalInspectionMode.current) {
                        VideoPlayerPreview(controller = controller)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    PlayerHeroCard(state = state, onRefresh = onRefresh, onAddFolder = { folderLauncher.launch(null) })
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text("Search tracks or folders") },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.folders.forEach { folder ->
                            FolderPill(folder = folder, onRemove = { onRemoveFolder(folder) })
                        }
                        AssistChip(
                            onClick = { folderLauncher.launch(null) },
                            label = { Text("Add folder") },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        )
                    }
                }
            }
            if (state.media.isEmpty()) {
                item {
                    EmptyLibraryCard(scanning = state.scanning)
                }
            } else {
                itemsIndexed(state.media, key = { _, item -> item.uriString }) { _, item ->
                    MediaRow(
                        item = item,
                        active = state.playerState.currentUri == item.uriString,
                        onClick = { onPlay(item) },
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(90.dp))
            }
        }
    }
}

@Composable
private fun PlayerHeroCard(
    state: PlayerUiState,
    onRefresh: () -> Unit,
    onAddFolder: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        ),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (state.playerState.currentUri != null) state.playerState.currentTitle else "Compact local playback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (state.playerState.currentUri != null) {
                            "${MediaSupport.displayKind(state.playerState.currentUri)} • ${if (state.playerState.isPlaying) "playing" else "paused"}"
                        } else {
                            "MP3 and MP4 folders, background play, saved resume"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onRefresh, label = { Text("Rescan") })
                    AssistChip(onClick = onAddFolder, label = { Text("Add") })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactStat(label = "folders", value = state.folders.size.toString(), modifier = Modifier.weight(1f))
                CompactStat(label = "files", value = state.media.size.toString(), modifier = Modifier.weight(1f))
                CompactStat(label = "theme", value = if (state.amoled) "amoled" else "white", modifier = Modifier.weight(1f))
                CompactStat(label = "scan", value = if (state.scanning) "busy" else "ready", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderPill(
    folder: MediaFolder,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun MediaRow(
    item: MediaEntry,
    active: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        onClick = onClick,
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
            Column(horizontalAlignment = Alignment.End) {
                Icon(
                    if (active) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (item.isVideo) "video" else "audio",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VideoPlayerPreview(controller: MediaController) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    player = controller
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { it.player = controller },
        )
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
                AssistChip(onClick = { onJumpBy(-10_000L) }, label = { Text("-10s") })
                IconButton(onClick = onSkipPrevious, enabled = playerState.hasPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = onSkipNext, enabled = playerState.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                AssistChip(onClick = { onJumpBy(10_000L) }, label = { Text("+10s") })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
