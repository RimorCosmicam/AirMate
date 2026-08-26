import Foundation
import CoreGraphics
import AirMatePrivateCG

protocol VirtualDisplayBackend: AnyObject {
    var displayID: CGDirectDisplayID { get }
    func stop()
}

enum DisplayError: LocalizedError {
    case creation(String)
    var errorDescription: String? {
        if case let .creation(message) = self { return message }
        return nil
    }
}

final class CoreGraphicsVirtualDisplayBackend: VirtualDisplayBackend {
    private var handle: AMVirtualDisplayHandle?
    let displayID: CGDirectDisplayID

    init(
        width: UInt32 = 1920,
        height: UInt32 = 1080,
        refreshRate: Double = 60,
        hiDPI: Bool = true
    ) throws {
        var id: CGDirectDisplayID = 0
        var errorPointer: UnsafePointer<CChar>?
        guard let created = AMVirtualDisplayCreate("AirMate Display", width, height, refreshRate, hiDPI, &id, &errorPointer) else {
            throw DisplayError.creation(errorPointer.map(String.init(cString:)) ?? "Unknown virtual display error")
        }
        handle = created
        displayID = id
    }

    func stop() {
        if let handle { AMVirtualDisplayDestroy(handle) }
        handle = nil
    }

    deinit { stop() }
}
