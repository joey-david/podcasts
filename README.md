# Podcasts

`Podcasts` is a compact local Android media player for MP3 and MP4 files.

## What It Does

- Adds local folders through the system folder picker
- Scans audio and video files from selected folders
- Plays audio in the background with Media3
- Persists the last played item and position
- Prompts to resume where playback left off
- Supports audio and video playback from the same library
- Includes quick seek controls for `-15s`, `-5s`, `+5s`, and `+15s`
- Supports playback speed, shuffle, and repeat controls
- Lets you long-press a library row to inspect the full title and media details
- Includes an Anki audio screen backed by generated card narration

## UI Notes

- Compact monochrome interface
- White / AMOLED theme toggle
- Single add-folder action in the bottom-right FAB
- Search field for filtering tracks and folders
- School icon switches between local media and Anki audio

## Anki Audio

Generate and sync narrated Anki cards from this workstation while Anki Desktop is open:

```bash
python3 scripts/export_anki_audio.py --query 'deck:*' --jobs 4 --embed-in-anki --sync
./gradlew :app:assembleDebug
```

The exporter uses AnkiConnect read/update/media endpoints only. It does not answer
cards or call review endpoints, so listening in Podder does not mark cards as
reviewed. Japanese `japadict` cards are spoken as `The meaning of [word] is
[translation].` The exporter synthesizes the Japanese word as a separate
`ja-JP` voice segment, so it is not pronounced by the English voice.

On Android, Podder first tries to read the synced AnkiDroid collection through
AnkiDroid's content provider using `deck:*` and `is:due` searches. It only uses
read-only provider queries and maps synced `podder-*` sound tags back to the
bundled audio assets. If AnkiDroid is not installed or permission is not
granted, Podder falls back to the bundled export manifest.

## Tech

- Kotlin
- Jetpack Compose
- AndroidX Media3
- SharedPreferences
- DocumentFile + MediaStore

## Build

Open the project in Android Studio with:

- Android SDK 35
- JDK 17

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

- The player is designed for local files only.
- On very low-memory emulators, Android may kill the app process aggressively while testing playback. The app-side playback crash caused by duplicate DataStore access has been fixed.
