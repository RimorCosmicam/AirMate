import Foundation

/// Control messages the Android client sends to drive this Mac, and the status the Mac sends
/// back. Video is one-way and untouched; these two share its socket. See `protocol/PROTOCOL.md`.
enum ControlPacket {
    static let magic: UInt32 = 0x414d4331
    static let version: UInt8 = 1
    static let headerBytes = 8

    enum ScrollPhase: UInt8 { case begin = 0, continued = 1, ended = 2 }

    enum Command: Equatable {
        case hello
        case start
        case stop
        case setDisplay(width: Int, height: Int, hiDPI: Bool)
        case requestIDR
        /// Coordinates are normalised across the streamed display, `0` to `65535` on each axis.
        case click(x: UInt16, y: UInt16)
        case scroll(phase: ScrollPhase, x: UInt16, y: UInt16, dx: Int16, dy: Int16)
        /// What the client's own panel measures, in its pixels and current orientation.
        case clientDisplay(width: UInt16, height: UInt16, maxWidth: UInt16, maxHeight: UInt16)

        /// Whether obeying this would change what the Mac is doing.
        ///
        /// `hello` only names a video destination, which the broadcast hello already does, so it
        /// is always honoured. Everything else waits for the user to authorise the sender.
        var changesState: Bool {
            if case .hello = self { return false }
            return true
        }
    }

    static func parse(_ buffer: [UInt8], count: Int) -> Command? {
        guard count >= headerBytes,
              buffer.readBE32(0) == magic,
              buffer[4] == version else { return nil }
        let payloadLength = Int(buffer.readBE16(6))
        guard headerBytes + payloadLength <= count else { return nil }

        switch buffer[5] {
        case 1: return .hello
        case 2: return .start
        case 3: return .stop
        case 4:
            guard payloadLength >= 5 else { return nil }
            let width = Int(buffer.readBE16(headerBytes))
            let height = Int(buffer.readBE16(headerBytes + 2))
            // A zero dimension would reach CGVirtualDisplay and be rejected there, but the
            // failure would read as a private-API problem rather than a bad datagram.
            guard width > 0, height > 0 else { return nil }
            return .setDisplay(width: width, height: height, hiDPI: buffer[headerBytes + 4] & 1 != 0)
        case 5: return .requestIDR
        case 6:
            guard payloadLength >= 4 else { return nil }
            return .click(x: buffer.readBE16(headerBytes), y: buffer.readBE16(headerBytes + 2))
        case 7:
            guard payloadLength >= 9, let phase = ScrollPhase(rawValue: buffer[headerBytes]) else { return nil }
            return .scroll(
                phase: phase,
                x: buffer.readBE16(headerBytes + 1),
                y: buffer.readBE16(headerBytes + 3),
                dx: Int16(bitPattern: buffer.readBE16(headerBytes + 5)),
                dy: Int16(bitPattern: buffer.readBE16(headerBytes + 7))
            )
        case 8:
            guard payloadLength >= 4 else { return nil }
            let width = buffer.readBE16(headerBytes)
            let height = buffer.readBE16(headerBytes + 2)
            guard width > 0, height > 0 else { return nil }
            // Older clients send only their panel size and no decoder ceiling.
            guard payloadLength >= 8 else {
                return .clientDisplay(width: width, height: height, maxWidth: 0, maxHeight: 0)
            }
            return .clientDisplay(
                width: width,
                height: height,
                maxWidth: buffer.readBE16(headerBytes + 4),
                maxHeight: buffer.readBE16(headerBytes + 6)
            )
        default: return nil
        }
    }
}

enum StatusPacket {
    static let magic: UInt32 = 0x414d5331
    static let version: UInt8 = 1
    static let bytes = 20

    static func datagram(
        running: Bool,
        hiDPI: Bool,
        authorised: Bool,
        width: Int,
        height: Int,
        encodedFrames: UInt64
    ) -> Data {
        var data = Data(capacity: bytes)
        data.appendBE(magic)
        data.append(version)
        data.append((running ? 1 : 0) | (hiDPI ? 2 : 0) | (authorised ? 4 : 0))
        data.appendBE(UInt16(clamping: width))
        data.appendBE(UInt16(clamping: height))
        data.appendBE(UInt16(0))
        data.appendBE(encodedFrames)
        return data
    }
}

private extension Array where Element == UInt8 {
    func readBE16(_ offset: Int) -> UInt16 {
        UInt16(self[offset]) << 8 | UInt16(self[offset + 1])
    }

    func readBE32(_ offset: Int) -> UInt32 {
        UInt32(self[offset]) << 24 | UInt32(self[offset + 1]) << 16
            | UInt32(self[offset + 2]) << 8 | UInt32(self[offset + 3])
    }
}
