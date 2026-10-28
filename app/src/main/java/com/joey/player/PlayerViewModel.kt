package com.joey.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.joey.player.anki.AnkiSpeech
import com.joey.player.playback.PlaybackBus
import com.joey.player.playback.PlaybackService
import com.joey.player.playback.PodderCommands
import com.joey.player.playback.ProgressStore
import com.joey.player.playback.QueueItem
import com.joey.player.playback.toMediaItem
import com.joey.player.playback.toQueueItem
import com.joey.player.youtube.YouTubeDownloader
import com.joey.player.youtube.YouTubeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private data class LibraryInputs(
    val amoled: Boolean,
    val resume: ResumeState?,
    val folders: List<MediaFolder>,
    val ankiLibrary: AnkiAudioLibrary,
    val ankiStatus: String?,
)

private data class PlaybackInputs(
    val query: String,
    val media: List<MediaEntry>,
    val scanning: Boolean,
    val playerState: PlayerState,
    val youtube: YoutubeState,
)

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PlayerRepository(application)
    private val ankiSpeech = AnkiSpeech(application)
    private var playerListener: Player.Listener? = null
    private var progressJob: Job? = null
    private var ankiJob: Job? = null
    private var connecting: Job? = null
    private val allMedia = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    private val ankiCards = MutableStateFlow(AnkiAudioLibrary(emptyList(), ""))
    private val ankiStatus = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)
    private val youtube = MutableStateFlow(YoutubeState())
    private val playerState = MutableStateFlow(PlayerState())
    var controller by mutableStateOf<MediaController?>(null)
        private set

    init {
        ProgressStore.init(application)
    }

    private val libraryInputs = combine(
        repository.amoledFlow,
        repository.resumeFlow,
        folders,
        ankiCards,
        ankiStatus,
    ) { amoled, resume, foldersValue, ankiLibrary, status ->
        LibraryInputs(amoled, resume, foldersValue, ankiLibrary, status)
    }

    private val playbackInputs = combine(query, allMedia, scanning, playerState, youtube) { queryValue, media, scanningValue, playerValue, youtubeValue ->
        PlaybackInputs(queryValue, media, scanningValue, playerValue, youtubeValue)
    }

    private val sessionInputs = combine(ProgressStore.progress, PlaybackBus.sleep, PlaybackBus.effects) { progress, sleep, effects ->
        Triple(progress, sleep, effects)
    }

    val uiState = combine(libraryInputs, playbackInputs, sessionInputs) { library, playback, session ->
        val (progress, sleep, effects) = session
        val filtered = if (playback.query.isBlank()) {
            playback.media
        } else {
            playback.media.filter {
                it.title.contains(playback.query, ignoreCase = true) ||
                    it.folderName.contains(playback.query, ignoreCase = true)
            }
        }
        val continueListening = playback.media
            .filter { entry ->
                val saved = progress[entry.uriString]
                saved != null && !saved.finished && saved.positionMs > 30_000L && entry.uriString != playback.playerState.currentUri
            }
            .sortedByDescending { progress[it.uriString]?.updatedAt ?: 0L }
            .take(6)
        PlayerUiState(
            amoled = library.amoled,
            folders = library.folders,
            media = filtered,
            allMediaCount = playback.media.size,
            ankiCards = library.ankiLibrary.cards,
            ankiSource = library.ankiLibrary.source,
            ankiError = library.ankiLibrary.error,
            ankiStatus = library.ankiStatus,
            query = playback.query,
            scanning = playback.scanning,
            youtube = playback.youtube,
            resumeState = library.resume?.takeIf { saved ->
                playback.media.any { it.uriString == saved.uriString } &&
                    saved.positionMs > 5_000L &&
                    playback.playerState.currentUri == null
            },
            playerState = playback.playerState,
            progress = progress,
            sleep = sleep,
            effects = effects,
            continueListening = continueListening,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    init {
        loadFolders()
        // Reconnect to a session that is already playing (e.g. after reopening from the notification).
        ensureController { refreshPlayerState() }
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

    fun deleteMedia(entry: MediaEntry) {
        viewModelScope.launch {
            if (repository.deleteMedia(entry)) {
                if (playerState.value.currentUri == entry.uriString) {
                    controller?.stop()
                    repository.clearResume()
                }
                ProgressStore.reset(entry.uriString)
                rescan()
            }
        }
    }

    fun markPlayed(entry: MediaEntry) {
        ProgressStore.markPlayed(entry.uriString, entry.durationMs)
    }

    fun resetProgress(entry: MediaEntry) {
        ProgressStore.reset(entry.uriString)
    }

    fun setYoutubeMode(mode: YouTubeMode) {
        youtube.value = youtube.value.copy(mode = mode)
    }

    /** Text shared from the YouTube app (or anywhere) lands here. */
    fun handleSharedText(text: String) {
        val link = YouTubeDownloader.extractLink(text) ?: text.trim().takeIf { YouTubeDownloader.parseVideoId(it) != null }
        youtube.value = youtube.value.copy(
            pendingLink = link ?: text.trim(),
            status = if (link == null) "No YouTube link found in the shared text." else null,
        )
        if (link != null) downloadYoutube(link)
    }

    fun consumePendingLink() {
        youtube.value = youtube.value.copy(pendingLink = null)
    }

    fun downloadYoutube(url: String) {
        if (youtube.value.downloading) return
        val mode = youtube.value.mode
        viewModelScope.launch {
            youtube.value = youtube.value.copy(downloading = true, progress = null, status = "Starting download...")
            runCatching {
                repository.downloadYoutube(url, mode) { progress, status ->
                    youtube.value = youtube.value.copy(progress = progress, status = status.ifBlank { "Downloading..." })
                }
            }.onSuccess { file ->
                youtube.value = youtube.value.copy(progress = 1f, status = "Saved ${file.name}")
                rescan()
            }.onFailure { error ->
                youtube.value = youtube.value.copy(progress = null, status = error.message ?: "Download failed.")
            }
            youtube.value = youtube.value.copy(downloading = false)
        }
    }

    fun playFromVisibleList(entry: MediaEntry) {
        val currentList = uiState.value.media
        ensureController { target ->
            playList(currentList.map(MediaEntry::asQueueItem), currentList.indexOfFirst { it.uriString == entry.uriString }, null, target)
        }
    }

    fun playEntry(entry: MediaEntry) {
        val list = allMedia.value.filter { it.folderName == entry.folderName }.ifEmpty { listOf(entry) }
        ensureController { target ->
            playList(list.map(MediaEntry::asQueueItem), list.indexOfFirst { it.uriString == entry.uriString }.coerceAtLeast(0), null, target)
        }
    }

    fun playAnkiCards(cards: List<AnkiAudioCard>, startCard: AnkiAudioCard? = null) {
        if (cards.isEmpty()) return
        val startIndex = startCard?.let { selected -> cards.indexOfFirst { it.cardId == selected.cardId } }?.coerceAtLeast(0) ?: 0
        val ordered = cards.drop(startIndex)
        ankiJob?.cancel()
        ankiJob = viewModelScope.launch {
            var started = false
            ordered.forEachIndexed { index, card ->
                val uri = if (card.needsSpeech) {
                    ankiStatus.value = "Voicing card ${index + 1} of ${ordered.size}..."
                    ankiSpeech.clipFor(card.cardId, card.spokenText)?.toString()
                } else {
                    card.audioUriString
                } ?: return@forEachIndexed
                val item = QueueItem(uri, card.title, card.deckName, isVideo = false)
                val target = connectedController() ?: return@launch
                if (!started) {
                    playList(listOf(item), 0, 0L, target)
                    started = true
                } else {
                    target.addMediaItem(item.toMediaItem())
                }
            }
            ankiStatus.value = if (started) null else "No playable Anki cards (is a text-to-speech voice installed?)."
        }
    }

    fun resumeSaved() {
        val resume = uiState.value.resumeState ?: return
        val list = allMedia.value.filter { it.folderName == allMedia.value.firstOrNull { e -> e.uriString == resume.uriString }?.folderName }
        val index = list.indexOfFirst { it.uriString == resume.uriString }
        if (index >= 0) {
            ensureController { target ->
                playList(list.map(MediaEntry::asQueueItem), index, resume.positionMs, target)
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

    /** minutes > 0 sets a fade-out timer, [PodderCommands.SLEEP_END_OF_ITEM] stops after this episode, 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        sendCommand(PodderCommands.SLEEP, bundleOf(PodderCommands.ARG_MINUTES to minutes))
    }

    fun setSkipSilence(enabled: Boolean) {
        sendCommand(PodderCommands.SKIP_SILENCE, bundleOf(PodderCommands.ARG_ENABLED to enabled))
    }

    fun setVoiceBoost(enabled: Boolean) {
        sendCommand(PodderCommands.VOICE_BOOST, bundleOf(PodderCommands.ARG_ENABLED to enabled))
    }

    private fun sendCommand(action: String, args: android.os.Bundle) {
        ensureController { target ->
            target.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), args)
        }
    }

    private fun playList(list: List<QueueItem>, index: Int, seekMs: Long?, target: Player) {
        if (index !in list.indices) return
        val start = seekMs ?: ProgressStore.startPositionFor(list[index].mediaId)
        target.setMediaItems(list.map { it.toMediaItem() }, index, start)
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

    private suspend fun connectedController(): MediaController? {
        controller?.let { return it }
        connecting?.join()
        controller?.let { return it }
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val built = runCatching { MediaController.Builder(appContext, token).buildAsync().await() }.getOrNull() ?: return null
        controller?.let {
            built.release()
            return it
        }
        attachListener(built)
        controller = built
        startProgressUpdates()
        if (built.mediaItemCount == 0) applyPlaybackPrefs(built)
        refreshPlayerState()
        return built
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let { existing ->
            onReady(existing)
            return
        }
        val job = viewModelScope.launch {
            connectedController()?.let(onReady)
        }
        if (connecting?.isActive != true) connecting = job
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val target = controller
                if (target != null && (target.isPlaying || target.playbackState == Player.STATE_BUFFERING)) {
                    refreshPlayerState()
                    delay(500L)
                } else {
                    delay(1_000L)
                }
            }
        }
    }

    private fun attachListener(target: Player) {
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
        val queueItem = item?.toQueueItem()
        playerState.value = PlayerState(
            currentUri = item?.mediaId,
            currentTitle = queueItem?.title.orEmpty(),
            currentFolder = queueItem?.folder.orEmpty(),
            artworkUri = queueItem?.artworkUri,
            isPlaying = target.isPlaying,
            isVideo = queueItem?.isVideo == true,
            positionMs = target.currentPosition.coerceAtLeast(0L),
            durationMs = target.duration.takeIf { it > 0 } ?: 0L,
            playbackSpeed = target.playbackParameters.speed,
            hasNext = target.hasNextMediaItem(),
            hasPrevious = target.hasPreviousMediaItem(),
            shuffleEnabled = target.shuffleModeEnabled,
            repeatMode = target.repeatMode,
            queueSize = target.mediaItemCount,
            queueIndex = target.currentMediaItemIndex,
        )
    }

    private fun applyPlaybackPrefs(target: Player) {
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
        if (item.mediaId.startsWith("file:") && item.mediaId.contains("/anki_tts/")) return
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
                        isVideo = item.toQueueItem().isVideo,
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
        ankiJob?.cancel()
        ankiSpeech.shutdown()
        playerListener?.let { listener -> controller?.removeListener(listener) }
        // Only the controller is released; the service keeps playing in the background.
        controller?.release()
        super.onCleared()
    }
}
