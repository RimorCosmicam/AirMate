import Foundation
import os

struct StreamSnapshot: Sendable, Equatable {
    var captured: UInt64 = 0
    var submitted: UInt64 = 0
    var encoded: UInt64 = 0
    var droppedPending: UInt64 = 0
    var droppedNetwork: UInt64 = 0
    var pendingFrames: Int = 0
    var lastClientHelloNanos: UInt64 = 0
}

final class Diagnostics: @unchecked Sendable {
    static let shared = Diagnostics()
    private let lock = NSLock()
    private var value = StreamSnapshot()
    let displayLog = Logger(subsystem: "com.airmate.mac", category: "AirMate.Display")
    let captureLog = Logger(subsystem: "com.airmate.mac", category: "AirMate.Capture")
    let encoderLog = Logger(subsystem: "com.airmate.mac", category: "AirMate.Encoder")
    let networkLog = Logger(subsystem: "com.airmate.mac", category: "AirMate.Network")

    func mutate(_ body: (inout StreamSnapshot) -> Void) { lock.withLock { body(&value) } }
    func snapshot() -> StreamSnapshot { lock.withLock { value } }
}
