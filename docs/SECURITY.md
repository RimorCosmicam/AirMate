# Security status

The vertical-slice transport is **development-only**. The UDP hello and video are unauthenticated and unencrypted. Use only on an isolated trusted LAN.

Production pairing will add a reliable Network.framework control connection using TLS 1.3, CryptoKit P-256 ephemeral key agreement, a QR-carried one-time token/public-key fingerprint, Keychain-persisted device identities, explicit first-pair confirmation, and per-session traffic keys. Video datagrams will use ChaChaPoly or AES-GCM with a nonce derived from session/frame/fragment identifiers. Long-term private keys will never enter QR data.

The encrypted control channel must land before any release distribution. The packet header and latest-frame-wins behavior remain unchanged; authentication data is appended without replacing the transport architecture.

