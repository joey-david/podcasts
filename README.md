# Podcasts

`Podcasts` is a compact local Android media player for MP3 and MP4 files.

## What It Does

- Plays local audio and video folders (system folder picker), video rendered in-app
- Real background playback: a Media3 `MediaSessionService` with a lock-screen and
  shade media notification (artwork, **-15 s / +15 s**, previous/next); tapping
  it opens Podder
- Proper audio focus: starting Podder pauses Spotify and other players, and
  another player starting pauses Podder; unplugging headphones pauses
- Media buttons and "resume" on Bluetooth/lock screen go to Podder, even after
  the app was killed (playback resumption restores the last queue)
- Every episode remembers its position, shows a progress bar, and is marked
  played near the end; a "Continue listening" row picks up half-finished items
- Sleep timer (15/30/45/60 min with a gentle fade-out, or end of episode),
  skip silence, voice boost, speed 0.8–2x, shuffle, repeat
- **Save from YouTube**: share a video from the YouTube app (or paste a link)
  and Podder downloads it as audio (m4a) or video (≤720p mp4, muxed on device)
  with its thumbnail. No yt-dlp, Python or ffmpeg bundled
- Anki audio: listen to your cards hands-free (see below)

## Anki Audio

Podder reads cards from AnkiDroid's content provider (read-only, never marks
reviews). Cards with a pre-generated narration play that file; everything else
is voiced on the phone with Android text-to-speech, with Japanese runs spoken
by a Japanese voice. `japadict` cards are read as
"The meaning of [word] is [translation]."

High-quality narration is generated on the workstation and copied to the phone
instead of being bundled into the APK:

```bash
python3 scripts/export_anki_audio.py --query 'deck:*' --jobs 4 --embed-in-anki --sync
adb push generated/anki_audio /sdcard/Podcasts/
```

Then add the `Podcasts` folder with the + button. Podder finds
`anki_audio/manifest.json` inside any added folder.

## Tech

- Kotlin
- Jetpack Compose
- AndroidX Media3
- SharedPreferences
- YouTube InnerTube player API + MediaMuxer
- Android TextToSpeech
- DocumentFile + MediaStore

## Build

Needs JDK 17+ and the Android SDK (compileSdk 37 is downloaded by AGP).

Or build from the CLI:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Debug APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

Release APK output:

- `app/build/outputs/apk/release/app-release.apk`

## Notes

- Release APK is about 2 MB (R8, resource shrinking, trimmed ExoPlayer
  renderers/extractors). 1.x was 221 MB because it bundled yt-dlp, Python,
  ffmpeg and the generated Anki audio.
