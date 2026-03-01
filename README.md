# SoundLock 🔊

An Android app that delivers a forced, immersive audio experience.

## What it does

When opened, SoundLock:
1. Silently forces device volume to **maximum**
2. Enters full **immersive/lock mode** — hides navigation bars, covers screen with a black overlay, and sets brightness to zero
3. Plays `sound.mp3` in the **background** (even if power button is pressed)
4. After **10 seconds**, restores everything: volume, brightness, nav bars — and stops the music

## Setup

1. Add your `sound.mp3` file to `app/src/main/res/raw/`
2. Build & run on Android 8.0+ (API 26+)

## Permissions Used

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Run music in background |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background audio |
| `WAKE_LOCK` | Keep screen on |
| `POST_NOTIFICATIONS` | Required for foreground service (Android 13+) |

## Technical Notes

- Uses `WindowInsetsControllerCompat` for immersive mode
- `FLAG_SECURE` blocks external overlays and screenshots
- `FLAG_KEEP_SCREEN_ON` keeps app active
- Volume button presses are intercepted and reset to max
- `MusicService` runs as a foreground service with `mediaPlayback` type

## File Structure

```
app/
├── res/
│   ├── raw/
│   │   └── sound.mp3        ← Add your audio file here
│   └── ...
├── java/com/example/soundlock/
│   ├── MainActivity.kt      ← Main UI, lock logic, volume control
│   └── MusicService.kt      ← Background audio playback
└── AndroidManifest.xml
```
