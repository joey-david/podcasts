package com.joey.player

import android.net.Uri
import androidx.media3.common.Player
import com.joey.player.domain.MediaSupport
import com.joey.player.playback.AudioEffectsState
import com.joey.player.playback.ItemProgress
import com.joey.player.playback.QueueItem
import com.joey.player.playback.SleepTimerState
import com.joey.player.youtube.YouTubeMode

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
    val artworkUri: String? = null,
) {
    fun asQueueItem(): QueueItem = QueueItem(uriString, title, folderName, isVideo, artworkUri)
}

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
    val origin: String = "Imported Anki audio folder",
) {
    /** No exported clip was found, so the phone's voice reads the card. */
    val needsSpeech: Boolean
        get() = audioUriString.isBlank()

    val playbackId: String
        get() = audioUriString.ifBlank { "anki-tts:$cardId" }

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
    val currentFolder: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isVideo: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
)

data class PlaybackPrefs(
    val speed: Float = 1f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class YoutubeState(
    val downloading: Boolean = false,
    val progress: Float? = null,
    val status: String? = null,
    val pendingLink: String? = null,
    val mode: YouTubeMode = YouTubeMode.AUDIO,
)

data class PlayerUiState(
    val amoled: Boolean = false,
    val folders: List<MediaFolder> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
    val allMediaCount: Int = 0,
    val ankiCards: List<AnkiAudioCard> = emptyList(),
    val ankiSource: String = "",
    val ankiError: String? = null,
    val ankiStatus: String? = null,
    val query: String = "",
    val scanning: Boolean = false,
    val youtube: YoutubeState = YoutubeState(),
    val resumeState: ResumeState? = null,
    val playerState: PlayerState = PlayerState(),
    val progress: Map<String, ItemProgress> = emptyMap(),
    val sleep: SleepTimerState = SleepTimerState(),
    val effects: AudioEffectsState = AudioEffectsState(),
    val continueListening: List<MediaEntry> = emptyList(),
)

data class FolderMediaGroup(
    val folder: MediaFolder,
    val items: List<MediaEntry>,
)

internal object AnkiDroidContract {
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
