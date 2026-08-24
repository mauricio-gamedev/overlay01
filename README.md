# Overlay01

Overlay01 is a lightweight Android streaming app focused on gameplay capture and URL-based web overlays.

## Project goal

Build a stable, low-overhead pipeline for vertical mobile livestreams:

`MediaProjection -> GPU compositor -> Web overlay -> MediaCodec -> RTMP/RTMPS`

The project intentionally stays small. Stability, low memory usage and recoverability have priority over feature count.

## Core rule: never block game input

Overlay01 does not place a `TYPE_APPLICATION_OVERLAY` window over the game. The URL overlay is rendered on a private off-screen display and composited only into the outgoing video frame. The game therefore keeps ownership of touch input even when the user's finger passes over the visual position of the overlay in the livestream.

## v0.1 pipeline

- Native Android UI with no Compose dependency
- One HTTP/HTTPS overlay URL with hardware WebView rendering
- Persistent overlay URL and `Fix overlay` lock
- MediaProjection screen capture through a real `VirtualDisplay`
- Gameplay frames consumed directly as an external OpenGL ES texture
- Private off-screen WebView rendered as a second external OpenGL texture
- 720x1280 (9:16) GPU compositor with game aspect ratio preserved
- Premultiplied-alpha URL overlay composition
- No Bitmap/frame copy through the CPU in the gameplay video path
- MediaCodec H.264 hardware Surface encoder
- 720x1280, 30 FPS, 3 Mbps video baseline
- MediaCodec AAC-LC audio at 44.1 kHz mono / 128 kbps
- Android playback capture for game audio when the target app permits it
- Microphone mode and microphone fallback path
- RTMP/RTMPS publishing through RootEncoder's mature RTMP transport
- Basic reconnect attempts and forced H.264 keyframe after reconnect
- Foreground service lifecycle with Stop action
- Synchronized OpenGL shutdown before releasing the encoder Surface
- Video timestamps normalized from zero for A/V synchronization

## Milestones

- [x] Repository initialized
- [x] Android application skeleton
- [x] MediaProjection capture session
- [x] GPU compositor
- [x] URL overlay engine
- [x] Hardware H.264 video encoding
- [x] AAC audio capture/encoding
- [x] RTMP/RTMPS publishing
- [x] Live foreground service wiring
- [x] Single overlay URL persistence + lock
- [x] Debug APK compiles in GitHub Actions
- [ ] Physical-device live validation and tuning

## Build validation

The current v0.1 `main` code was validated by the repository's `Android Debug APK` GitHub Actions workflow on 2026-08-24. `:app:assembleDebug` and artifact upload completed successfully.

Build configuration:

- `minSdk 26`
- `targetSdk 36`
- `compileSdk 37`
- H.264: 720x1280 / 30 FPS / 3 Mbps
- AAC: 44.1 kHz / mono / 128 kbps

## Audio note

Android playback capture is permission- and app-policy-dependent. Some games/apps prohibit their internal audio from being captured. Overlay01 also supports microphone capture so the live can still use an audio source when internal playback capture is unavailable.

## Current status

v0.1 core pipeline is implemented and build-validated. The next checkpoint is a real-device live test to measure stability, thermals, dropped frames, overlay refresh behavior, game-audio compatibility and RTMP behavior under network changes.
