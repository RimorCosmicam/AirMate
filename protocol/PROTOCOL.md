# AirMate Wire Protocol v2

All integer fields are unsigned and network byte order (big endian). Everything travels over UDP port 48620. Video datagram size is capped at 1200 bytes to avoid IP fragmentation on typical LANs.

Three message families share the port, told apart by their magic: `AMV1` video (Mac to Android), `AMC1` control (Android to Mac), and `AMS1` status (Mac to Android).

## Discovery hello

The Android client broadcasts the 8-byte ASCII message `AMHELLO1` to UDP port 48620 once per second until video arrives. The Mac uses the source address and port as its video destination. Scanning the Mac's QR code is an optional shortcut that names the host directly; it is not required, and pairing never depends on the camera.

This bootstrap is unauthenticated and must be replaced by the authenticated control channel described in `docs/SECURITY.md`.

## Video datagram

The fixed header is 40 bytes followed by at most 1160 payload bytes.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `AMV1` (`0x414d5631`) |
| 4 | 1 | protocol version (`1`) |
| 5 | 1 | flags: bit 0 keyframe, bit 1 codec config, bit 2 HEVC |
| 6 | 2 | header bytes (`40`) |
| 8 | 8 | session ID |
| 16 | 8 | frame ID |
| 24 | 8 | capture timestamp, monotonic nanoseconds |
| 32 | 2 | fragment index, zero based |
| 34 | 2 | fragment count |
| 36 | 2 | payload length |
| 38 | 2 | reserved (`0`) |

The encoded access unit is Annex-B byte stream. HEVC keyframes carry VPS/SPS/PPS before slice NAL units. A receiver must discard an incomplete access unit when it observes a higher frame ID. There is no video retransmission.

## Control datagram

Sent by Android so the tablet can drive the Mac's display without the user walking back to it. The header is 8 bytes followed by the payload named for the type.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `AMC1` (`0x414d4331`) |
| 4 | 1 | protocol version (`1`) |
| 5 | 1 | type |
| 6 | 2 | payload length |

| Type | Name | Payload |
|---:|---|---|
| 1 | hello | none — equivalent to `AMHELLO1` |
| 2 | start display | none |
| 3 | stop display | none |
| 4 | set display | `u16` width, `u16` height, `u8` flags (bit 0 HiDPI) |
| 5 | request IDR | none |
| 6 | click | `u16` x, `u16` y |
| 7 | scroll | `u8` phase (0 begin, 1 continue, 2 end), `u16` x, `u16` y, `i16` dx, `i16` dy |
| 8 | client display | `u16` width, `u16` height |

`client display` is the only message that tells the host anything rather than asking it for
something: the client's own panel size, in its own pixels and current orientation. The host has no
other way to know it, and without it the only resolution it can name is the one it already chose.

Click coordinates are normalised: `0` is the left or top edge of the streamed display and `65535`
the right or bottom, whatever its resolution. The host maps them onto its own display bounds, so
neither side has to agree on pixels, points, or whether the display is HiDPI — the one place that
distinction would otherwise leak into the protocol and be wrong on exactly half the configurations.

Scroll deltas are in the streamed display's pixels, positive down and right, and are sent only
between a `begin` and an `end`. The phase exists so the host can move the pointer to the gesture
once and put it back once, rather than teleporting it on every delta of a flick.

`set display` carrying the running configuration is a no-op rather than a restart, so a client that repeats its state does not tear the display down.

Clicking and scrolling is the whole input vocabulary — reading mode, not a second pointing device.
There is no drag, no right click and no modifier, and the host restores the cursor to where it was
before every gesture: a second screen you touch should not steal the pointer from the screen you
are working on.

### Who may send one

Pairing is the authorisation. The client currently receiving video is the client whose commands are obeyed; a datagram from any other address is discarded. There is no second consent step, because a device that is already being sent the screen gains nothing by also being asked whether it may change it.

This is not authentication. The transport is unencrypted and an attacker who can forge a source address, or who wins the race to be the paired client, can drive the host. See `docs/SECURITY.md`.

## Status datagram

Sent by the Mac to the paired client once per second, and immediately on any state change. It is what lets the Android control card describe the Mac while the display is stopped and no video is flowing. Fixed 20 bytes.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `AMS1` (`0x414d5331`) |
| 4 | 1 | protocol version (`1`) |
| 5 | 1 | flags: bit 0 display running, bit 1 HiDPI, bit 2 sender authorised for control |
| 6 | 2 | display width |
| 8 | 2 | display height |
| 10 | 2 | reserved (`0`) |
| 12 | 8 | encoded frame count |

Bit 2 is addressed to the recipient: it reports whether *this* client's control datagrams will be obeyed, so the card can say to look at the Mac rather than silently doing nothing.

## Lifecycle

`idle -> hello -> streaming -> interrupted -> hello`. Frame IDs increase within a session. A new session ID invalidates all previous fragments and decoder reference state. A control message that changes the display ends the current session and begins a new one.
