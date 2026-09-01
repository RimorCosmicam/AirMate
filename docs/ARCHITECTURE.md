# Architecture

## Hot path

```text
CGVirtualDisplay -> SCStream -> IOSurface -> CVPixelBuffer
  -> VTCompressionSession -> Annex B -> bounded UDP
  -> one-slot reassembly -> MediaCodec -> Surface -> SurfaceView
```

No main-thread, bitmap, image, or file conversion exists in the normal video path.

## Latest-frame-wins invariants

- Capture hands each ScreenCaptureKit sample buffer straight to `LatestFrameEncoder` and returns.
  The stream's own queue depth is three, which is its documented minimum; nothing stale can
  accumulate past that because the encoder is still latest-frame-only.
- The encoder owns at most one submitted frame and one pending frame. A new pending frame replaces
  the previous one.
- The UDP socket is non-blocking. `EAGAIN` drops the remaining fragments of the current access
  unit.
- Android owns one fixed 8 MiB reassembly buffer. A newer frame resets it immediately; late
  fragments are ignored.
- Decoder input uses a zero-timeout buffer dequeue. If hardware decode is busy, the access unit is
  dropped rather than queued in application memory.

The last two are the *default*. `FrameLeniency` on the client relaxes both together — how many
newer frames an incomplete access unit survives, and how long the decoder waits for an input
buffer — trading latency for keeping frames. `ACTUAL` is the behaviour described above.

## The Android UI is not resident

While pairing there is a Compose hierarchy: a Mont card over scrolling diagonal stripes. When the
first frame decodes, the stripes part along their own axis to reveal the stream already running
behind them, and then the `ComposeView` is removed from the layout and its composition disposed.
During streaming the view hierarchy is a `FrameLayout` and an `AspectSurfaceView`, nothing else.

The controls come back on an inward edge swipe, detected by `EdgeGestureDetector` on the root view
rather than by the system back gesture: the swipe edge is only available through predictive back on
API 34 and up, and under `IMMERSIVE_STICKY` the first system edge swipe is swallowed revealing the
system bars. The card opens on the side the swipe came from.

Rotation is `SENSOR_LANDSCAPE` or `SENSOR_PORTRAIT`, never free. The Mac streams one resolution
whichever way the tablet is held, so `AspectSurfaceView` letterboxes rather than stretching, and
`configChanges` keeps the activity — and therefore the decoder and socket — alive across a turn.

## Control and status

Android drives the Mac over the same UDP port the video leaves by: `AMC1` control up, `AMS1` status
down at 1 Hz. Status is what lets the control card describe a display that is stopped and sending
no video at all. State-changing control is gated on a human authorising the sender in the Mac's
window; see `docs/SECURITY.md`.

## Private API boundary

Only `AirMatePrivateCG.mm` declares `CGVirtualDisplay*` classes. Swift receives an opaque handle
and a `CGDirectDisplayID`. Runtime `NSClassFromString` checks turn a missing class into a reported
startup failure rather than a crash. Selector-level drift within a class that still exists is not
currently checked and would still fault.

## Mont

Both clients are built on the Mont design language (`~/Projects/Mont`): black at 92%, square
corners, and Mont Black carrying the hierarchy that decoration used to. The macOS window is SwiftUI
(`MontKit.swift`), the Android client is Compose (`ui/mont/`), and the two share the surface alpha,
the stripe geometry, the toggle, and the row grammar so they cannot drift apart.
