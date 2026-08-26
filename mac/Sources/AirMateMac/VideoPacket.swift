import Foundation

enum VideoPacket {
    static let magic: UInt32 = 0x414d5631
    static let version: UInt8 = 1
    static let headerBytes = 40
    static let maximumDatagramBytes = 1200
    static let maximumPayloadBytes = maximumDatagramBytes - headerBytes

    static func datagram(sessionID: UInt64, frameID: UInt64, captureNanos: UInt64,
                         fragmentIndex: UInt16, fragmentCount: UInt16, flags: UInt8,
                         payload: Data.SubSequence) -> Data {
        var data = Data(capacity: headerBytes + payload.count)
        data.appendBE(magic)
        data.append(version)
        data.append(flags)
        data.appendBE(UInt16(headerBytes))
        data.appendBE(sessionID)
        data.appendBE(frameID)
        data.appendBE(captureNanos)
        data.appendBE(fragmentIndex)
        data.appendBE(fragmentCount)
        data.appendBE(UInt16(payload.count))
        data.appendBE(UInt16(0))
        data.append(contentsOf: payload)
        return data
    }
}

extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }
}

