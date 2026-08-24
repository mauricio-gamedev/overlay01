# Overlay01

Overlay01 v0.4 is a lightweight Android app for URL-based web overlays over games.

## Current scope

- up to 2 independent HTTPS overlay URLs
- one transparent touch-through Android overlay window
- 2 hardware-accelerated WebViews inside the same window
- Overlay 2 is kept above Overlay 1
- show/hide each overlay independently
- scale each overlay from 40% to 100%
- position each overlay on X and Y from -50% to +50%
- independent Fixar option for each overlay
- URL, visibility, scale, position and lock state persist between app launches
- moving/resizing the same URL reuses the existing WebView without reload
- automatic WebView recreation if its renderer process exits
- foreground service keeps the active overlays alive
- no external runtime libraries
- no RTMP, screen capture, audio or encoder pipeline

v0.3 settings are migrated into Overlay 1 on first launch after updating to v0.4.

## Build

- version: 0.4.0
- minSdk: 26
- targetSdk: 36
- compileSdk: 36

The debug build is validated by GitHub Actions before delivery.
