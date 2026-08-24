# Overlay01

Overlay01 is a lightweight Android streaming app focused on gameplay capture and URL-based web overlays.

## Project goal

Build a stable, low-overhead pipeline for vertical mobile livestreams:

`MediaProjection -> GPU compositor -> Web overlays -> MediaCodec -> RTMP/RTMPS`

The project intentionally starts small. Stability, low memory usage and recoverability are higher priorities than adding many features.

## Core rule: never block game input

Overlay01 does not place a `TYPE_APPLICATION_OVERLAY` window over the game. Web overlays will be composited off-screen into the outgoing video frame instead. This keeps the captured game responsible for its own touch input, even when the user's finger passes over the visual position where an overlay appears in the livestream.

## Milestones

- [x] Repository initialized
- [x] Android application skeleton
- [x] MediaProjection capture session
- [x] GPU compositor
- [ ] URL overlay engine
- [ ] Hardware video encoding
- [ ] RTMP/RTMPS publishing
- [ ] Live foreground service hardening
- [ ] Saved overlay layouts

## Initial architecture

- `capture`: screen-capture lifecycle and MediaProjection
- `overlay`: URL/web overlay models and lifecycle
- `render`: compositor and GPU-facing rendering code
- `encode`: MediaCodec video/audio encoding
- `stream`: RTMP/RTMPS transport
- `service`: foreground live-session lifetime
- `ui`: thin configuration and preview layer

## Current Stage 3 baseline

- Native Android app with no Compose dependency
- URL overlay preview through a hardware-accelerated WebView
- JavaScript and DOM storage enabled for interactive overlay pages
- Overlay URL persisted locally after a successful load
- Persistent `Fix overlay` control that locks overlay editing and restores after reopening the app
- MediaProjection permission flow wired from the activity
- Dedicated `mediaProjection` foreground service lifecycle
- Real `VirtualDisplay` capture session
- Screen frames consumed directly by an external OpenGL ES texture
- No Bitmap/frame copy through the CPU in the capture path
- Capture runs on its own `HandlerThread`
- Captured-content resize support for orientation/size changes
- No system overlay window, so gameplay touches are not intercepted by Overlay01
- Fixed 720x1280 (9:16) GPU composition canvas
- Game aspect ratio preserved with no stretching
- Unused vertical canvas area remains available for URL overlays
- SurfaceTexture transform matrix applied by the compositor
- Debug APK workflow prepared for GitHub Actions

## Status

Stage 3 GPU compositor created. Next: render the first URL/WebView overlay into the same GPU composition path without creating a touch-blocking system overlay window.
