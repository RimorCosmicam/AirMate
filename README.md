# AirMate

AirMate is a native macOS-to-Android extended display prototype. The Mac creates a real virtual display, captures only that display, hardware-encodes it with VideoToolbox, and sends bounded, non-retransmitted UDP video to an Android `MediaCodec` decoder rendering directly into a `SurfaceView`.

> [!WARNING]
> `CGVirtualDisplay` is a private CoreGraphics API. It is isolated in `mac/Sources/AirMatePrivateCG` so it can be replaced without changing the capture, encoder, transport, or UI layers. A build using this API is not eligible for the Mac App Store.

## Repository

- `mac/` — Swift/AppKit menu-bar host, virtual display, `CGDisplayStream`, VideoToolbox, UDP sender
- `android/` — Kotlin Android client, UDP reassembly, hardware `MediaCodec`, direct `SurfaceView`
- `protocol/` — versioned wire protocol
- `docs/` — architecture, security boundaries, and test plans
- `.github/workflows/ci.yml` — GitHub-hosted macOS and Android builds

## Current vertical slice

1. Launch AirMate on the Mac.
2. Choose **Start Display** from the menu bar. AirMate creates a 1920x1080 virtual display and listens for a client hello on UDP port 48620.
3. Launch the Android app on the same LAN. It automatically broadcasts a hello and listens for video.
4. Arrange **AirMate Display** in macOS Display Settings.

Every latency-sensitive boundary is bounded. Capture allows one VideoToolbox submission and one replaceable pending pixel buffer. UDP sends are non-blocking and an access unit is abandoned on backpressure. Android keeps one reusable reassembly slot and discards an incomplete frame when a newer frame appears.

The first milestone intentionally does not claim production security: the discovery hello is unauthenticated and streaming is not yet encrypted. See `docs/SECURITY.md`. Do not use this build on an untrusted LAN.

## Builds

Builds run on GitHub Actions. The workflow uses a GitHub-hosted macOS toolchain for the Swift package and an Ubuntu runner for Android. No checked-in secrets or signing identities are required for debug compilation.

Each successful workflow publishes two test artifacts: `AirMate-macOS-arm64.zip` containing an ad-hoc-signed `.app` for Apple Silicon, and `app-debug.apk` for Android. Tagged prereleases copy these into the GitHub Releases page for straightforward installation.

## Installing test builds

On macOS, unzip the archive, move `AirMate.app` into Applications, then Control-click it and choose **Open** the first time. Approve any Screen Recording permission macOS requests. This development build is ad-hoc signed, not notarized.

AirMate opens a visible window on first launch and also shows an icon-only menu-bar item. Closing the window leaves the menu-bar service running. Choose **Quit AirMate** from that menu when you want to remove or replace the app.

On Android, download `app-debug.apk`, allow installation from the browser or file manager when prompted, and install it. The current vertical slice uses a UDP LAN bootstrap rather than production pairing, so both devices must be on the same isolated/trusted Wi-Fi network.
