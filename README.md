# Overlay01

Overlay01 is a lightweight Android streaming app focused on gameplay capture and URL-based web overlays.

## Project goal

Build a stable, low-overhead pipeline for vertical mobile livestreams:

`MediaProjection -> GPU compositor -> Web overlays -> MediaCodec -> RTMP/RTMPS`

The project intentionally starts small. Stability, low memory usage and recoverability are higher priorities than adding many features.

## Milestones

- [x] Repository initialized
- [ ] Android application skeleton
- [ ] MediaProjection capture session
- [ ] GPU compositor
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

## Status

Early development / Stage 1.
