import XCTest
@testable import AirMateMac

final class ControlPacketTests: XCTestCase {
    private func header(type: UInt8, payload: [UInt8] = []) -> [UInt8] {
        var bytes: [UInt8] = [0x41, 0x4d, 0x43, 0x31, 1, type]
        bytes.append(UInt8(payload.count >> 8))
        bytes.append(UInt8(payload.count & 0xff))
        bytes.append(contentsOf: payload)
        return bytes
    }

    func testParsesSetDisplay() throws {
        let bytes = header(type: 4, payload: [0x07, 0x80, 0x04, 0x38, 1])
        XCTAssertEqual(
            ControlPacket.parse(bytes, count: bytes.count),
            .setDisplay(width: 1920, height: 1080, hiDPI: true)
        )
    }

    func testRejectsForeignMagicAndShortPayload() {
        var wrongMagic = header(type: 2)
        wrongMagic[0] = 0x42
        XCTAssertNil(ControlPacket.parse(wrongMagic, count: wrongMagic.count))

        // Claims five payload bytes but only carries two.
        let truncated = header(type: 4, payload: [0x07, 0x80])
        var lying = truncated
        lying[7] = 5
        XCTAssertNil(ControlPacket.parse(lying, count: lying.count))
    }

    func testRejectsZeroDimensions() {
        let bytes = header(type: 4, payload: [0, 0, 0x04, 0x38, 0])
        XCTAssertNil(ControlPacket.parse(bytes, count: bytes.count))
    }

    /// Only `hello` may be obeyed without a human agreeing to it.
    func testOnlyHelloIsStateless() {
        XCTAssertFalse(ControlPacket.Command.hello.changesState)
        for command in [ControlPacket.Command.start, .stop, .requestIDR,
                        .setDisplay(width: 1920, height: 1080, hiDPI: false)] {
            XCTAssertTrue(command.changesState)
        }
    }

    func testParsesClickAtNormalisedCentre() throws {
        let bytes = header(type: 6, payload: [0x7f, 0xff, 0x7f, 0xff])
        XCTAssertEqual(
            ControlPacket.parse(bytes, count: bytes.count),
            .click(x: 0x7fff, y: 0x7fff)
        )
    }

    func testParsesScrollWithNegativeDelta() throws {
        // dx of -1 arrives as 0xffff and must come back signed, not as 65535 pixels rightwards.
        let bytes = header(type: 7, payload: [1, 0, 0, 0, 0, 0xff, 0xff, 0, 40])
        XCTAssertEqual(
            ControlPacket.parse(bytes, count: bytes.count),
            .scroll(phase: .continued, x: 0, y: 0, dx: -1, dy: 40)
        )
    }

    func testRejectsShortClickAndUnknownPhase() {
        let short = header(type: 6, payload: [0, 0])
        XCTAssertNil(ControlPacket.parse(short, count: short.count))
        let badPhase = header(type: 7, payload: [9, 0, 0, 0, 0, 0, 0, 0, 0])
        XCTAssertNil(ControlPacket.parse(badPhase, count: badPhase.count))
    }

    /// Click and scroll change what the Mac is doing and must be gated like everything else.
    func testClickAndScrollChangeState() {
        XCTAssertTrue(ControlPacket.Command.click(x: 0, y: 0).changesState)
        XCTAssertTrue(ControlPacket.Command.scroll(phase: .begin, x: 0, y: 0, dx: 0, dy: 0).changesState)
    }

    func testStatusDatagramLayout() {
        let packet = StatusPacket.datagram(
            running: true, hiDPI: false, authorised: true,
            width: 1280, height: 800, encodedFrames: 7
        )
        XCTAssertEqual(packet.count, StatusPacket.bytes)
        XCTAssertEqual(Array(packet.prefix(6)), [0x41, 0x4d, 0x53, 0x31, 1, 0b101])
        XCTAssertEqual(Array(packet[6..<10]), [0x05, 0x00, 0x03, 0x20])
        XCTAssertEqual(packet.last, 7)
    }
}
