import XCTest
@testable import AirMateMac

final class UDPSenderTests: XCTestCase {
    func testExplicitCloseImmediatelyReleasesListeningPort() throws {
        var firstSender: UDPSender?
        var selectedPort: UInt16?

        for _ in 0..<20 {
            let candidate = UInt16.random(in: 49_152...65_000)
            if let sender = try? UDPSender(port: candidate) {
                firstSender = sender
                selectedPort = candidate
                break
            }
        }

        let first = try XCTUnwrap(firstSender)
        let port = try XCTUnwrap(selectedPort)
        first.close()
        first.close()

        let replacement = try UDPSender(port: port)
        replacement.close()
    }
}
