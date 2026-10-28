package com.joey.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.joey.player.MainActivity
import com.joey.player.R
import com.joey.player.domain.MediaSupport
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PodderCommands {
    const val SEEK_BACK = "podder.SEEK_BACK_15"
    const val SEEK_FORWARD = "podder.SEEK_FORWARD_15"
    const val SLEEP = "podder.SLEEP"
    const val SKIP_SILENCE = "podder.SKIP_SILENCE"
    const val VOICE_BOOST = "podder.VOICE_BOOST"
    const val ARG_MINUTES = "minutes"
    const val ARG_ENABLED = "enabled"
    const val SLEEP_END_OF_ITEM = -1
    const val JUMP_MS = 15_000L

    val all = listOf(SEEK_BACK, SEEK_FORWARD, SLEEP, SKIP_SILENCE, VOICE_BOOST)
}

data class SleepTimerState(
    val endsAtMs: Long? = null,
    val endOfItem: Boolean = false,
)

data class AudioEffectsState(
    val skipSilence: Boolean = false,
    val voiceBoost: Boolean = false,
)

/** Same-process state the service publishes for the UI. */
object PlaybackBus {
    internal val sleepState = MutableStateFlow(SleepTimerState())
    internal val effectsState = MutableStateFlow(AudioEffectsState())
    val sleep: StateFlow<SleepTimerState> = sleepState.asStateFlow()
    val effects: StateFlow<AudioEffectsState> = effectsState.asStateFlow()
}

const val EXTRA_IS_VIDEO = "podder.is_video"
const val EXTRA_FOLDER = "podder.folder"

fun QueueItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(mediaId)
        .setMimeType(MediaSupport.inferMimeType(mediaId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(folder)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(bundleOf(EXTRA_IS_VIDEO to isVideo, EXTRA_FOLDER to folder))
                .build(),
        )
        .build()

fun MediaItem.toQueueItem(): QueueItem =
    QueueItem(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        folder = mediaMetadata.extras?.getString(EXTRA_FOLDER) ?: mediaMetadata.artist?.toString().orEmpty(),
        isVideo = mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true ||
            mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO,
        artworkUri = mediaMetadata.artworkUri?.toString(),
    )

class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var loudness: LoudnessEnhancer? = null
    private var sleepJob: Job? = null
    private val settings by lazy { getSharedPreferences("slate_player", Context.MODE_PRIVATE) }

    private val seekBackButton by lazy {
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setDisplayName("Back 15 seconds")
            .setSessionCommand(SessionCommand(PodderCommands.SEEK_BACK, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .build()
    }
    private val seekForwardButton by lazy {
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
            .setDisplayName("Forward 15 seconds")
            .setSessionCommand(SessionCommand(PodderCommands.SEEK_FORWARD, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    }
    private val previousButton by lazy {
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }
    private val nextButton by lazy {
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }
    private val buttons get() = listOf(seekBackButton, seekForwardButton, previousButton, nextButton)

    override fun onCreate() {
        super.onCreate()
        ProgressStore.init(this)
        // Only the renderers/extractors a local podcast player needs, so R8 can drop subtitle parsers & co.
        val renderers = RenderersFactory { handler, videoListener, audioListener, _, _ ->
            arrayOf(
                MediaCodecVideoRenderer(this, MediaCodecSelector.DEFAULT, 5_000L, handler, videoListener, 50),
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener),
            )
        }
        val extractors = ExtractorsFactory {
            arrayOf(
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                Mp3Extractor(),
                MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
                OggExtractor(),
                FlacExtractor(),
                WavExtractor(),
                AdtsExtractor(),
            )
        }
        player = ExoPlayer.Builder(this, renderers, ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this), extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // Request audio focus: Spotify & co. pause when Podder starts, and Podder pauses when they start.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(PodderCommands.JUMP_MS)
            .setSeekForwardIncrementMs(PodderCommands.JUMP_MS)
            .build()
        player.addListener(progressListener)

        PlaybackBus.effectsState.value = AudioEffectsState(
            skipSilence = settings.getBoolean(KEY_SKIP_SILENCE, false),
            voiceBoost = settings.getBoolean(KEY_VOICE_BOOST, false),
        )
        applyEffects()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_podder)
            },
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .setMediaButtonPreferences(buttons)
            .setCallback(SessionCallback())
            .build()

        scope.launch {
            while (isActive) {
                delay(5_000L)
                if (player.isPlaying) persistProgress()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistProgress()
        scope.cancel()
        loudness?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun persistProgress() {
        val item = player.currentMediaItem ?: return
        ProgressStore.save(item.mediaId, player.currentPosition, player.duration.takeIf { it > 0L } ?: 0L)
        persistQueue()
    }

    private fun persistQueue() {
        if (player.mediaItemCount == 0) return
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it).toQueueItem() }
        ProgressStore.saveQueue(SavedQueue(items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0L)))
    }

    private fun jump(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    private fun applyEffects() {
        val effects = PlaybackBus.effectsState.value
        player.skipSilenceEnabled = effects.skipSilence
        loudness?.release()
        loudness = null
        if (effects.voiceBoost && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            loudness = runCatching {
                LoudnessEnhancer(player.audioSessionId).apply {
                    setTargetGain(VOICE_BOOST_MB)
                    enabled = true
                }
            }.getOrNull()
        }
        settings.edit()
            .putBoolean(KEY_SKIP_SILENCE, effects.skipSilence)
            .putBoolean(KEY_VOICE_BOOST, effects.voiceBoost)
            .apply()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        when {
            minutes == PodderCommands.SLEEP_END_OF_ITEM -> {
                player.pauseAtEndOfMediaItems = true
                PlaybackBus.sleepState.value = SleepTimerState(endOfItem = true)
            }
            minutes <= 0 -> PlaybackBus.sleepState.value = SleepTimerState()
            else -> {
                val endsAt = System.currentTimeMillis() + minutes * 60_000L
                PlaybackBus.sleepState.value = SleepTimerState(endsAtMs = endsAt)
                sleepJob = scope.launch {
                    delay((endsAt - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0L))
                    // Fade out gently instead of cutting the voice mid-sentence.
                    val steps = 40
                    repeat(steps) { step ->
                        player.volume = 1f - (step + 1f) / steps
                        delay(FADE_MS / steps)
                    }
                    player.pause()
                    player.volume = 1f
                    PlaybackBus.sleepState.value = SleepTimerState()
                }
            }
        }
    }

    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val oldItem = oldPosition.mediaItem ?: return
            if (oldItem.mediaId == newPosition.mediaItem?.mediaId) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                ProgressStore.markPlayed(oldItem.mediaId, oldPosition.positionMs)
                if (PlaybackBus.sleepState.value.endOfItem) {
                    PlaybackBus.sleepState.value = SleepTimerState()
                    player.pauseAtEndOfMediaItems = false
                }
            } else {
                ProgressStore.get(oldItem.mediaId)?.let {
                    ProgressStore.save(oldItem.mediaId, oldPosition.positionMs, it.durationMs)
                }
            }
            persistQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player.currentMediaItem?.let { ProgressStore.markPlayed(it.mediaId, player.duration) }
                if (PlaybackBus.sleepState.value.endOfItem) setSleepTimer(0)
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applyEffects()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                .apply { PodderCommands.all.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PodderCommands.SEEK_BACK -> jump(-PodderCommands.JUMP_MS)
                PodderCommands.SEEK_FORWARD -> jump(PodderCommands.JUMP_MS)
                PodderCommands.SLEEP -> setSleepTimer(args.getInt(PodderCommands.ARG_MINUTES, 0))
                PodderCommands.SKIP_SILENCE -> {
                    PlaybackBus.effectsState.value = PlaybackBus.effectsState.value.copy(
                        skipSilence = args.getBoolean(PodderCommands.ARG_ENABLED),
                    )
                    applyEffects()
                }
                PodderCommands.VOICE_BOOST -> {
                    PlaybackBus.effectsState.value = PlaybackBus.effectsState.value.copy(
                        voiceBoost = args.getBoolean(PodderCommands.ARG_ENABLED),
                    )
                    applyEffects()
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Controllers (including Bluetooth/system UI) may send items without a URI; rebuild them from the id.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { item -> item.toQueueItem().toMediaItem() })

        // Lets a headset/Bluetooth play press resume Podder even after the service was killed,
        // instead of Android handing the button to the last other media app.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val queue = ProgressStore.loadQueue()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val current = queue.items[queue.index]
            val start = ProgressStore.get(current.mediaId)?.positionMs ?: queue.positionMs
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(queue.items.map { it.toMediaItem() }, queue.index, start),
            )
        }
    }

    private companion object {
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_VOICE_BOOST = "voice_boost"
        const val VOICE_BOOST_MB = 900
        const val FADE_MS = 10_000L
    }
}
