# Architecture

## Hot path

```text
CGVirtualDisplay -> CGDisplayStream -> IOSurface -> CVPixelBuffer
  -> VTCompressionSession -> Annex B -> bounded UDP
  -> one-slot reassembly -> MediaCodec -> Surface -> SurfaceView
```

No main-thread, bitmap, image, or file conversion exists in the normal video path.

## Latest-frame-wins invariants

- Capture callback creates an IOSurface-backed pixel buffer and returns immediately after handing it to `LatestFrameEncoder`.
- The encoder owns at most one submitted frame and one pending frame. A new pending frame replaces the previous one.
- The UDP socket is non-blocking. `EAGAIN` drops the remaining fragments of the current access unit.
- Android owns one fixed 8 MiB reassembly buffer. A newer frame resets it immediately; late fragments are ignored.
- Decoder input uses a zero-timeout buffer dequeue. If hardware decode is busy, the access unit is dropped rather than queued in application memory.

## Private API boundary

Only `AirMatePrivateCG.mm` declares `CGVirtualDisplay*` classes. Swift receives an opaque handle and a `CGDirectDisplayID`. Runtime class/selector checks turn API drift into a reported startup failure rather than an unrecognized-selector crash.

