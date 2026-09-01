# Test plan

## GitHub CI

- Compile the macOS Swift package on a macOS runner.
- Compile Android debug APK and run JVM unit tests on Ubuntu.
- Protocol tests verify byte order, bounds, and replacement semantics.

## Required hardware gates

CI cannot prove private WindowServer behavior, hardware codecs, LAN latency, or DRM. Before release, run on an M1 MacBook Pro and Galaxy Tab A7-class tablet:

- 1080p60 for 8 hours; fail on rising queue depth, memory, or glass-to-glass latency trend.
- Induce 1%, 5%, and burst packet loss; verify drops/recovery rather than latency accumulation.
- Interrupt Wi-Fi, sleep/wake both endpoints, rotate/background Android, and recreate the virtual display.
- Confirm `VTSessionCopyProperty(...UsingHardwareAcceleratedVideoEncoder)` and reject Android software decoder names in development.
- Play protected content on the physical display and verify AirMate does not capture it or unnecessarily disrupt it. Protected content moved to AirMate may be black; bypass is explicitly out of scope.

## Interface gates

- Run onboarding from a clean install on both sides; confirm it does not reappear afterwards.
- Confirm the tablet's Compose view is gone once streaming: the hierarchy should be a `FrameLayout`
  and an `AspectSurfaceView` and nothing else.
- Swipe in from each edge and confirm the card opens on that side.
- Rotate the tablet through both directions of each axis and confirm the picture follows without
  the activity being recreated and without the stream stretching.
- Change resolution and HiDPI from the tablet; confirm the Mac rebuilds the display, and that a
  datagram from an unauthorised address changes nothing until it is confirmed on the Mac.
- Walk each `FrameLeniency` setting under induced loss and confirm higher settings keep more frames
  and cost latency, rather than doing nothing.

