import Foundation
import CoreGraphics
import AirMatePrivateCG

protocol VirtualDisplayBackend: AnyObject {
    var displayID: CGDirectDisplayID { get }
    /// Change the display's mode without replacing it. Throws if this display will not take it.
    func resize(width: UInt32, height: UInt32, refreshRate: Double, hiDPI: Bool) throws
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

    func resize(width: UInt32, height: UInt32, refreshRate: Double, hiDPI: Bool) throws {
        guard let handle else { throw DisplayError.creation("The virtual display is already gone") }
        var errorPointer: UnsafePointer<CChar>?
        guard AMVirtualDisplayApplyMode(handle, width, height, refreshRate, hiDPI, &errorPointer) else {
            throw DisplayError.creation(errorPointer.map(String.init(cString:)) ?? "Unknown virtual display error")
        }
    }

    func stop() {
        if let handle { AMVirtualDisplayDestroy(handle) }
        handle = nil
    }

    deinit { stop() }
}
