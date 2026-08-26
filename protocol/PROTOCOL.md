# AirMate Wire Protocol v1

All integer fields are unsigned and network byte order (big endian). Video uses UDP. Datagram size is capped at 1200 bytes to avoid IP fragmentation on typical LANs.

## Discovery hello

The Android client broadcasts the 8-byte ASCII message `AMHELLO1` to UDP port 48620 once per second until video arrives. The prototype Mac uses the source address and port as its video destination. This bootstrap is deliberately temporary and must be replaced by the authenticated control channel described in `docs/SECURITY.md`.

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

## Lifecycle

`idle -> hello -> streaming -> interrupted -> hello`. Frame IDs increase within a session. A new session ID invalidates all previous fragments and decoder reference state. The future authenticated control protocol reserves messages for capabilities, start/stop, telemetry, bitrate changes, and IDR requests.

