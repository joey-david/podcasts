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

## UI Notes

- Compact monochrome interface
- White / AMOLED theme toggle
- Single add-folder action in the bottom-right FAB
- Search field for filtering tracks and folders

## Tech

- Kotlin
- Jetpack Compose
- AndroidX Media3
- DataStore
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
