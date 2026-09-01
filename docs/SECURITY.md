# Security status

The transport is **development-only**. Discovery, video, control and status are all
unauthenticated and unencrypted. Use only on an isolated trusted LAN.

## What changed with the control channel

Protocol v2 lets the Android client change what the Mac is doing: start and stop the display,
change its resolution, ask for a keyframe. Before it existed, the worst an attacker on the LAN
could do was receive video. Now they could restart your display.

The mitigation is a human, not a key. A control datagram that changes state is refused until
someone has authorised that source address in the Mac's own window, and the datagram that raised
the question is discarded rather than queued — a command that arrived before anyone agreed to it
does not take effect the moment they do. Authorisation is per address and lasts only for the life
of that sender.

Be clear about what this is not:

- It is not authentication. Nothing is signed and nothing is verified.
- It is replayable, and an attacker who can forge a source address can act as the authorised
  client.
- It protects the *Mac's* state only. Video is still readable by anyone on the segment.

It exists so that a device on your network cannot restart your display without a person agreeing
once, and it claims nothing further.

## What production needs

A reliable Network.framework control connection using TLS 1.3, CryptoKit P-256 ephemeral key
agreement, a QR-carried one-time token and public-key fingerprint, Keychain-persisted device
identities, explicit first-pair confirmation, and per-session traffic keys. Video datagrams will
use ChaChaPoly or AES-GCM with a nonce derived from session, frame and fragment identifiers.
Long-term private keys will never enter QR data.

The encrypted control channel must land before any release distribution. The packet headers and
latest-frame-wins behaviour remain unchanged; authentication data is appended without replacing
the transport architecture.
