# Ballbox Machine Ads Sync

Standalone Android sidecar repo for Ballbox machine ad/media sync.

## Scope
- one activity
- manual `Sync now` button
- no Compose
- no emulator setup
- no polling yet
- no auth yet
- no APK restart automation yet

## Why this shape
The goal is to keep project size and machine complexity low for the first real test.
Current source tree size is intentionally tiny.

## Current defaults
- manifest URL: `https://ballbox.app/api/machines/2601070188/ads-manifest`
- target root: `/sdcard/TcnFoldercopy`

## Current behavior
- fetch manifest JSON
- download changed files only
- validate sha256
- stream downloads to disk instead of holding videos in memory
- write exact relative paths under target root
- reject absolute paths and path traversal outside target root
- show logs on screen
- report restart policy from backend

## Build
The Gradle wrapper is included. Build with JDK 17 or newer and Android SDK 34:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Storage access
The default target is shared storage. On Android 11 or newer, the first sync opens
the system "All files access" screen. Grant access, return to the app, and tap
`Sync now` again. Android 10 and older use the normal storage permission prompt.

## Next step
- install on the vending machine
- test one folder-driven slot first
