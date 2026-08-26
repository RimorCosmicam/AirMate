import XCTest
@testable import AirMateMac

final class VideoPacketTests: XCTestCase {
    func testHeaderAndBound() {
        let payload = Data(repeating: 0xaa, count: VideoPacket.maximumPayloadBytes)
        let packet = VideoPacket.datagram(sessionID: 2, frameID: 3, captureNanos: 4,
                                          fragmentIndex: 0, fragmentCount: 1, flags: 5,
                                          payload: payload[...])
        XCTAssertEqual(packet.count, 1200)
        XCTAssertEqual(Array(packet.prefix(8)), [0x41, 0x4d, 0x56, 0x31, 1, 5, 0, 40])
    }
}

