# Overlay01

Overlay01 v0.2 is a lightweight Android app focused on one URL-based web layout.

## Current scope

- one HTTPS layout URL
- transparent hardware-accelerated WebView
- persistent foreground service
- saved URL and Fixar link option
- input passes through to the game while the layout is active
- automatic WebView recreation if the renderer process exits
- no external runtime libraries

The old capture, encoder, audio and RTMP code was removed. The project now contains only the minimal UI and overlay service needed for this purpose.

## Build

- version: 0.2.0
- minSdk: 26
- targetSdk: 36
- compileSdk: 36

The debug build is validated by GitHub Actions.
