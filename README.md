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
- write exact relative paths under target root
- show logs on screen
- report restart policy from backend

## Important note
This project was scaffolded without installing an emulator on `ballbox-first`.
It is not yet built on this machine because Android build tooling/wrapper is not present locally.
The code is ready for Android Studio or an existing Gradle/SDK setup.

## Next step
- open in Android Studio on a machine with Android SDK
- add Gradle wrapper there
- build APK
- install on the vending machine
- test one folder-driven slot first
