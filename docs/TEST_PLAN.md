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

