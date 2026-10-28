package com.joey.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.joey.player.playback.AudioEffectsState
import com.joey.player.playback.ItemProgress
import com.joey.player.playback.PodderCommands
import com.joey.player.playback.SleepTimerState
import com.joey.player.ui.theme.SlatePlayerTheme
import com.joey.player.youtube.YouTubeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PlayerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                        player = viewModel.controller,
                        actions = PlayerActions(
                            onQueryChange = viewModel::setQuery,
                            onRefresh = viewModel::rescan,
                            onPlay = viewModel::playFromVisibleList,
                            onPlayEntry = viewModel::playEntry,
                            onPlayAnkiCards = viewModel::playAnkiCards,
                            onRemoveFolder = viewModel::removeFolder,
                            onDownloadYoutube = viewModel::downloadYoutube,
                            onYoutubeMode = viewModel::setYoutubeMode,
                            onConsumePendingLink = viewModel::consumePendingLink,
                            onDeleteMedia = viewModel::deleteMedia,
                            onMarkPlayed = viewModel::markPlayed,
                            onResetProgress = viewModel::resetProgress,
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
                            onSleepTimer = viewModel::setSleepTimer,
                            onSkipSilence = viewModel::setSkipSilence,
                            onVoiceBoost = viewModel::setVoiceBoost,
                            onFolderPicked = viewModel::addFolder,
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        viewModel.handleSharedText(text)
    }
}

private class PlayerActions(
    val onQueryChange: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onPlay: (MediaEntry) -> Unit,
    val onPlayEntry: (MediaEntry) -> Unit,
    val onPlayAnkiCards: (List<AnkiAudioCard>, AnkiAudioCard?) -> Unit,
    val onRemoveFolder: (MediaFolder) -> Unit,
    val onDownloadYoutube: (String) -> Unit,
    val onYoutubeMode: (YouTubeMode) -> Unit,
    val onConsumePendingLink: () -> Unit,
    val onDeleteMedia: (MediaEntry) -> Unit,
    val onMarkPlayed: (MediaEntry) -> Unit,
    val onResetProgress: (MediaEntry) -> Unit,
    val onResume: () -> Unit,
    val onDismissResume: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onJumpBy: (Long) -> Unit,
    val onSkipNext: () -> Unit,
    val onSkipPrevious: () -> Unit,
    val onSpeedChange: (Float) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeatMode: () -> Unit,
    val onSleepTimer: (Int) -> Unit,
    val onSkipSilence: (Boolean) -> Unit,
    val onVoiceBoost: (Boolean) -> Unit,
    val onFolderPicked: (Uri, String) -> Unit,
)

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerScreen(
    state: PlayerUiState,
    player: Player?,
    actions: PlayerActions,
) {
    val context = LocalContext.current
    var inspectedMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var youtubeDialogOpen by rememberSaveable { mutableStateOf(false) }
    var youtubeUrl by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<MediaEntry?>(null) }
    var collapsedFolders by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showingAnki by rememberSaveable { mutableStateOf(false) }
    var ankiDueOnly by rememberSaveable { mutableStateOf(false) }
    var ankiCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        val treeName = DocumentFile.fromTreeUri(context, uri)?.name
            ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
                .ifBlank { "Folder" }
        actions.onFolderPicked(uri, treeName)
    }

    LaunchedEffect(state.youtube.pendingLink) {
        state.youtube.pendingLink?.let {
            youtubeUrl = it
            youtubeDialogOpen = true
            actions.onConsumePendingLink()
        }
    }

    if (youtubeDialogOpen) {
        YoutubeDialog(
            url = youtubeUrl,
            onUrlChange = { youtubeUrl = it },
            youtube = state.youtube,
            onModeChange = actions.onYoutubeMode,
            onDownload = { actions.onDownloadYoutube(youtubeUrl) },
            onClose = { youtubeDialogOpen = false },
        )
    }

    deleteCandidate?.let { media ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete download?") },
            text = { Text("Remove \"${media.title}\" from the YouTube folder.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteMedia(media)
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
            onDismissRequest = actions.onDismissResume,
            title = { Text("Resume last session?") },
            text = {
                Text(
                    "${state.resumeState.title} at ${formatTime(state.resumeState.positionMs)}",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onResume()
                    actions.onDismissResume()
                }) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissResume) {
                    Text("Not now")
                }
            },
        )
    }

    inspectedMedia?.let { media ->
        val progress = state.progress[media.uriString]
        AlertDialog(
            onDismissRequest = { inspectedMedia = null },
            title = { Text(media.title) },
            text = {
                Text(
                    buildString {
                        append(if (media.isVideo) "Video" else "Audio")
                        append("\nFolder: ")
                        append(media.folderName)
                        append("\nDuration: ")
                        append(formatTime(progress?.durationMs ?: media.durationMs))
                        if (progress != null) {
                            append("\nProgress: ")
                            append(if (progress.finished) "played" else "${formatTime(progress.positionMs)} (${(progress.fraction * 100).toInt()}%)")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (progress?.finished == true) actions.onResetProgress(media) else actions.onMarkPlayed(media)
                    inspectedMedia = null
                }) {
                    Text(if (progress?.finished == true) "Mark unplayed" else "Mark played")
                }
            },
            dismissButton = {
                Row {
                    if (progress != null && !progress.finished) {
                        TextButton(onClick = {
                            actions.onResetProgress(media)
                            inspectedMedia = null
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { inspectedMedia = null }) {
                        Text("Close")
                    }
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
                            if (showingAnki) "anki audio" else "${state.allMediaCount} episodes · audio + video",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                    }
                },
                actions = {
                    IconButton(onClick = { showingAnki = !showingAnki }) {
                        Icon(Icons.Rounded.School, contentDescription = if (showingAnki) "Show local library" else "Show Anki audio")
                    }
                    IconButton(onClick = { youtubeDialogOpen = true }) {
                        Icon(Icons.Rounded.SmartDisplay, contentDescription = "Download from YouTube")
                    }
                },
            )
        },
        bottomBar = {
            if (state.playerState.currentUri != null) {
                MiniPlayerBar(
                    playerState = state.playerState,
                    player = player,
                    sleep = state.sleep,
                    effects = state.effects,
                    actions = actions,
                )
            }
        },
        floatingActionButton = {
            if (state.playerState.currentUri == null) {
                FilledIconButton(onClick = { folderLauncher.launch(null) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add folder")
                }
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
        val ankiCategories = remember(state.ankiCards) { state.ankiCards.map { it.category }.distinct().sorted() }
        val filteredCards = remember(state.ankiCards, ankiDueOnly, ankiCategory) {
            state.ankiCards.filter { card ->
                (!ankiDueOnly || card.isDue) && (ankiCategory == null || card.category == ankiCategory)
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("Search tracks or folders") },
                )
            }
            if (showingAnki) {
                item {
                    AnkiAudioHeader(
                        cards = state.ankiCards,
                        filteredCount = filteredCards.size,
                        categories = ankiCategories,
                        selectedCategory = ankiCategory,
                        dueOnly = ankiDueOnly,
                        source = state.ankiSource,
                        error = state.ankiError,
                        status = state.ankiStatus,
                        onDueOnly = { ankiDueOnly = it },
                        onCategory = { ankiCategory = it },
                        onPlayAll = { actions.onPlayAnkiCards(filteredCards, null) },
                    )
                }
                if (state.ankiCards.isEmpty()) {
                    item { EmptyAnkiCard() }
                } else if (filteredCards.isEmpty()) {
                    item {
                        Text(
                            "No cards match the current Anki audio filters.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                } else {
                    items(filteredCards, key = { "anki-${it.cardId}" }) { card ->
                        AnkiAudioRow(
                            card = card,
                            active = state.playerState.currentTitle == card.title && state.playerState.currentFolder == card.deckName,
                            onClick = { actions.onPlayAnkiCards(filteredCards, card) },
                        )
                    }
                }
            } else if (state.media.isEmpty()) {
                item {
                    EmptyLibraryCard(scanning = state.scanning)
                }
            } else {
                if (state.continueListening.isNotEmpty() && state.query.isBlank()) {
                    item(key = "continue") {
                        ContinueListeningRow(
                            items = state.continueListening,
                            progress = state.progress,
                            onPlay = actions.onPlayEntry,
                        )
                    }
                }
                folderGroups.forEach { group ->
                    item(key = "folder-${group.folder.uriString}") {
                        FolderHeaderRow(
                            folder = group.folder,
                            fileCount = group.items.size,
                            playedCount = group.items.count { state.progress[it.uriString]?.finished == true },
                            collapsed = group.folder.uriString in collapsedFolders,
                            removable = !group.folder.uriString.startsWith("virtual:"),
                            onToggle = {
                                collapsedFolders = if (group.folder.uriString in collapsedFolders) {
                                    collapsedFolders - group.folder.uriString
                                } else {
                                    collapsedFolders + group.folder.uriString
                                }
                            },
                            onRemove = { actions.onRemoveFolder(group.folder) },
                        )
                    }
                    if (group.folder.uriString !in collapsedFolders) {
                        items(group.items, key = { item -> "${group.folder.uriString}|${item.uriString}" }) { item ->
                            MediaRow(
                                item = item,
                                active = state.playerState.currentUri == item.uriString,
                                playing = state.playerState.isPlaying,
                                progress = state.progress[item.uriString],
                                onClick = { actions.onPlay(item) },
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

@Composable
private fun YoutubeDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    youtube: YoutubeState,
    onModeChange: (YouTubeMode) -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!youtube.downloading) onClose() },
        title = { Text("Save from YouTube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    enabled = !youtube.downloading,
                    label = { Text("Paste YouTube link") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = youtube.mode == YouTubeMode.AUDIO,
                        onClick = { onModeChange(YouTubeMode.AUDIO) },
                        enabled = !youtube.downloading,
                        label = { Text("Audio only") },
                    )
                    FilterChip(
                        selected = youtube.mode == YouTubeMode.VIDEO,
                        onClick = { onModeChange(YouTubeMode.VIDEO) },
                        enabled = !youtube.downloading,
                        label = { Text("Video ≤720p") },
                    )
                }
                Text(
                    "Tip: share any video from the YouTube app to Podder to grab it in one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                youtube.status?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (youtube.downloading) {
                    val progress = youtube.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !youtube.downloading,
                onClick = onDownload,
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(if (youtube.downloading) "Hide" else "Close")
            }
        },
    )
}

@Composable
private fun ContinueListeningRow(
    items: List<MediaEntry>,
    progress: Map<String, ItemProgress>,
    onPlay: (MediaEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Continue listening",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.uriString }) { item ->
                val saved = progress[item.uriString]
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .clickable { onPlay(item) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(item.artworkUri, item.uriString, item.isVideo, size = 36)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (saved != null) {
                            LinearProgressIndicator(progress = { saved.fraction }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatTime((saved.durationMs - saved.positionMs).coerceAtLeast(0L))} left",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnkiAudioHeader(
    cards: List<AnkiAudioCard>,
    filteredCount: Int,
    categories: List<String>,
    selectedCategory: String?,
    dueOnly: Boolean,
    source: String,
    error: String?,
    status: String?,
    onDueOnly: (Boolean) -> Unit,
    onCategory: (String?) -> Unit,
    onPlayAll: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Anki audio", fontWeight = FontWeight.SemiBold)
                    Text(
                        "$source • $filteredCount selected • ${cards.count { it.isDue }} due today • ${cards.size} total" +
                            cards.count { it.needsSpeech }.takeIf { it > 0 }?.let { " • $it phone-voiced" }.orEmpty(),
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
                Switch(checked = dueOnly, onCheckedChange = onDueOnly)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = selectedCategory == null, onClick = { onCategory(null) }, label = { Text("All") })
                categories.forEach { category ->
                    FilterChip(selected = selectedCategory == category, onClick = { onCategory(category) }, label = { Text(category) })
                }
            }
            (status ?: error)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            AssistChip(
                enabled = filteredCount > 0,
                onClick = onPlayAll,
                label = { Text(if (selectedCategory != null) "Play $selectedCategory" else "Play selected") },
                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun EmptyAnkiCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No Anki cards yet", fontWeight = FontWeight.SemiBold)
            Text(
                "Install AnkiDroid and allow access to listen to every card (your phone's voice reads cards without exported audio), or add the exported anki_audio folder with the + button.",
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
            .padding(horizontal = 16.dp)
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
                    imageVector = when {
                        active -> Icons.Rounded.PauseCircle
                        card.needsSpeech -> Icons.Rounded.RecordVoiceOver
                        else -> Icons.Rounded.School
                    },
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
                    "${card.deckName} • ${if (card.isDue) "due today" else "not due"} • ${card.origin.lowercase(Locale.US)}",
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
    playedCount: Int,
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
                    "$fileCount files" + if (playedCount > 0) " · $playedCount played" else "",
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
                    "Add folders with the + button, or share a YouTube video to Podder. Every episode remembers where you stopped."
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
    playing: Boolean,
    progress: ItemProgress?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val finished = progress?.finished == true
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.artworkUri, item.uriString, item.isVideo, size = 42, highlighted = active)
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = if (finished && !active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    val duration = progress?.durationMs?.takeIf { it > 0L } ?: item.durationMs
                    val detail = when {
                        finished -> "played"
                        progress != null && progress.positionMs > 5_000L ->
                            "${formatTime((duration - progress.positionMs).coerceAtLeast(0L))} left"
                        else -> formatTime(duration)
                    }
                    Text(
                        "${item.folderName} • $detail",
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
                                contentDescription = "Delete download",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(
                            when {
                                active && playing -> Icons.Rounded.PauseCircle
                                finished -> Icons.Rounded.CheckCircle
                                else -> Icons.Rounded.PlayCircle
                            },
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
            if (progress != null && !finished && progress.positionMs > 5_000L) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

/** Cover art from a sidecar .jpg (YouTube downloads) or the file's embedded picture, else a glyph. */
@Composable
private fun Artwork(
    artworkUri: String?,
    mediaUri: String,
    isVideo: Boolean,
    size: Int,
    highlighted: Boolean = false,
) {
    val context = LocalContext.current
    val bitmap by produceState(initialValue = ArtworkCache[mediaUri], artworkUri, mediaUri) {
        if (value != null || mediaUri.isBlank() || mediaUri in ArtworkCache.misses) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                artworkUri?.let { uri -> Uri.parse(uri).path?.let { decodeScaled(File(it).readBytes(), 160) } }
                    ?: MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, Uri.parse(mediaUri))
                        retriever.embeddedPicture?.let { decodeScaled(it, 160) }
                    }
            }.getOrNull()
        }.also { ArtworkCache.put(mediaUri, it) }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                imageVector = if (isVideo) Icons.Rounded.Movie else Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private object ArtworkCache {
    private val cache = android.util.LruCache<String, Bitmap>(60)
    val misses = mutableSetOf<String>()

    operator fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap?) {
        if (bitmap == null) misses += key else cache.put(key, bitmap)
    }
}

private fun decodeScaled(bytes: ByteArray, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun VideoSurface(player: Player?) {
    AndroidView(
        factory = { SurfaceView(it) },
        // Attach only when the player changes: re-attaching on every progress tick blanks the frame.
        update = { view ->
            if (view.tag !== player) {
                (view.tag as? Player)?.clearVideoSurfaceView(view)
                player?.setVideoSurfaceView(view)
                view.tag = player
            }
        },
        onRelease = { view ->
            (view.tag as? Player)?.clearVideoSurfaceView(view)
            view.tag = null
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp)),
    )
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiniPlayerBar(
    playerState: PlayerState,
    player: Player?,
    sleep: SleepTimerState,
    effects: AudioEffectsState,
    actions: PlayerActions,
) {
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep.endsAtMs) {
        while (sleep.endsAtMs != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (playerState.isVideo) {
                VideoSurface(player)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!playerState.isVideo) {
                    Artwork(playerState.artworkUri, playerState.currentUri.orEmpty(), false, size = 44, highlighted = true)
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(playerState.currentTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("${formatTime(playerState.positionMs)} / ${formatTime(playerState.durationMs)}")
                            if (playerState.queueSize > 1) append(" · ${playerState.queueIndex + 1}/${playerState.queueSize}")
                            if (playerState.playbackSpeed != 1f && playerState.durationMs > 0L) {
                                val remaining = ((playerState.durationMs - playerState.positionMs) / playerState.playbackSpeed).toLong()
                                append(" · ${formatTime(remaining)} left at ${playerState.playbackSpeed}x")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(onClick = actions.onTogglePlayPause, shape = CircleShape) {
                    Icon(
                        if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    )
                }
            }
            Slider(
                value = playerState.positionMs.toFloat().coerceAtMost(playerState.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = actions.onSeek,
                valueRange = 0f..playerState.durationMs.toFloat().coerceAtLeast(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(onClick = { actions.onJumpBy(-PodderCommands.JUMP_MS) }, label = { Text("-15s") })
                AssistChip(onClick = { actions.onJumpBy(-5_000L) }, label = { Text("-5s") })
                IconButton(onClick = actions.onSkipPrevious, enabled = playerState.hasPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = actions.onSkipNext, enabled = playerState.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                AssistChip(onClick = { actions.onJumpBy(5_000L) }, label = { Text("+5s") })
                AssistChip(onClick = { actions.onJumpBy(PodderCommands.JUMP_MS) }, label = { Text("+15s") })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    FilterChip(
                        selected = sleep.endsAtMs != null || sleep.endOfItem,
                        onClick = { sleepMenuOpen = true },
                        label = {
                            Text(
                                when {
                                    sleep.endOfItem -> "Sleep: end of episode"
                                    sleep.endsAtMs != null -> "Sleep ${formatTime((sleep.endsAtMs - now).coerceAtLeast(0L))}"
                                    else -> "Sleep"
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = sleepMenuOpen, onDismissRequest = { sleepMenuOpen = false }) {
                        listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
                            DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = {
                                actions.onSleepTimer(minutes)
                                sleepMenuOpen = false
                            })
                        }
                        DropdownMenuItem(text = { Text("End of episode") }, onClick = {
                            actions.onSleepTimer(PodderCommands.SLEEP_END_OF_ITEM)
                            sleepMenuOpen = false
                        })
                        if (sleep.endsAtMs != null || sleep.endOfItem) {
                            DropdownMenuItem(text = { Text("Turn off") }, onClick = {
                                actions.onSleepTimer(0)
                                sleepMenuOpen = false
                            })
                        }
                    }
                }
                FilterChip(
                    selected = effects.skipSilence,
                    onClick = { actions.onSkipSilence(!effects.skipSilence) },
                    label = { Text("Skip silence") },
                    leadingIcon = { Icon(Icons.Rounded.Forward10, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = effects.voiceBoost,
                    onClick = { actions.onVoiceBoost(!effects.voiceBoost) },
                    label = { Text("Voice boost") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = playerState.shuffleEnabled,
                    onClick = actions.onToggleShuffle,
                    label = { Text("Shuffle") },
                    leadingIcon = {
                        Icon(
                            if (playerState.shuffleEnabled) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                FilterChip(
                    selected = playerState.repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = actions.onCycleRepeatMode,
                    label = { Text(repeatModeLabel(playerState.repeatMode)) },
                    leadingIcon = {
                        Icon(
                            when (playerState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                                Player.REPEAT_MODE_ALL -> Icons.Rounded.RepeatOn
                                else -> Icons.Rounded.Repeat
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playerState.playbackSpeed == speed,
                        onClick = { actions.onSpeedChange(speed) },
                        label = { Text("${speed}x") },
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
