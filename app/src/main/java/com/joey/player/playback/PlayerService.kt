package com.joey.player.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.joey.player.PlayerKeys
import com.joey.player.ResumeState
import com.joey.player.data.playerStore
import com.joey.player.domain.MediaSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlayerService : MediaSessionService() {
    private lateinit var player: androidx.media3.exoplayer.ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistenceJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            serviceScope.launch { persistState() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                serviceScope.launch {
                    clearResume()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .build()
            .apply {
                setHandleAudioBecomingNoisy(true)
                addListener(listener)
            }
        mediaSession = MediaSession.Builder(this, player).build()
        persistenceJob = serviceScope.launch {
            while (true) {
                persistState()
                delay(1_000L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        runBlocking { persistState() }
        persistenceJob?.cancel()
        player.removeListener(listener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private suspend fun persistState() {
        val current = player.currentMediaItem ?: return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        if (duration > 0L && position >= duration - 1_500L) {
            clearResume()
            return
        }
        val title = current.mediaMetadata.title?.toString().orEmpty().ifBlank { "Last media" }
        val resume = ResumeState(
            uriString = current.mediaId,
            title = title,
            positionMs = position,
            durationMs = duration,
            isVideo = current.localConfiguration?.mimeType?.startsWith("video/") == true,
        )
        applicationContext.playerStore.edit { prefs ->
            prefs[PlayerKeys.lastUri] = resume.uriString
            prefs[PlayerKeys.lastTitle] = resume.title
            prefs[PlayerKeys.lastPosition] = resume.positionMs
            prefs[PlayerKeys.lastDuration] = resume.durationMs
            prefs[PlayerKeys.lastIsVideo] = resume.isVideo
        }
    }

    private suspend fun clearResume() {
        applicationContext.playerStore.edit { prefs ->
            prefs.remove(PlayerKeys.lastUri)
            prefs.remove(PlayerKeys.lastTitle)
            prefs.remove(PlayerKeys.lastPosition)
            prefs.remove(PlayerKeys.lastDuration)
            prefs.remove(PlayerKeys.lastIsVideo)
        }
    }
}
