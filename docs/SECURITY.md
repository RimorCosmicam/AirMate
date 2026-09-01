# Security status

The transport is **development-only**. Discovery, video, control and status are all
unauthenticated and unencrypted. Use only on an isolated trusted LAN.

## What changed with the control channel

Protocol v2 lets the Android client change what the host is doing: start and stop the display,
change its resolution, ask for a keyframe. Before it existed, the worst an attacker on the LAN
could do was receive video. Now they can restart your display.

Pairing is the only gate. The client currently being sent video is the client whose commands are
obeyed; anything from another address is discarded. There is deliberately no second consent step —
a device already receiving your screen gains nothing by also being asked whether it may change it,
and the prompt bought no security it did not already have.

Be clear about what that means:

- It is not authentication. Nothing is signed and nothing is verified.
- Whoever pairs first can drive the host, and a forged source address defeats the check entirely.
- It protects nothing about the video, which is still readable by anyone on the segment.

On a trusted LAN this is fine. On any other network it is not, and there is no setting that makes
it so.

## What production needs

A reliable Network.framework control connection using TLS 1.3, CryptoKit P-256 ephemeral key
agreement, a QR-carried one-time token and public-key fingerprint, Keychain-persisted device
identities, explicit first-pair confirmation, and per-session traffic keys. Video datagrams will
use ChaChaPoly or AES-GCM with a nonce derived from session, frame and fragment identifiers.
Long-term private keys will never enter QR data.

The encrypted control channel must land before any release distribution. The packet headers and
latest-frame-wins behaviour remain unchanged; authentication data is appended without replacing
the transport architecture.
