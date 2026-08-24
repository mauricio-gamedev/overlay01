# Overlay01

Overlay01 v0.3 is a lightweight Android app focused on one URL-based web layout.

## Current scope

- one HTTPS layout URL
- transparent hardware-accelerated WebView
- persistent foreground service
- saved URL and Fixar configuração option
- input passes through to the game while the layout is active
- overlay scale control from 40% to 100%
- scale persists between app launches
- resize updates do not reload or recreate the WebView
- same-URL updates reuse the existing WebView
- automatic WebView recreation if the renderer process exits
- no external runtime libraries

The old capture, encoder, audio and RTMP code remains removed. The project contains only the minimal UI and overlay service needed for this purpose.

## Build

- version: 0.3.0
- minSdk: 26
- targetSdk: 36
- compileSdk: 36

The debug build is validated by GitHub Actions before delivery.
