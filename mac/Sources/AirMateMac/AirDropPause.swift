import Foundation

/// Pausing AWDL, the radio link behind AirDrop, Sidecar, Universal Control and Continuity.
///
/// AWDL has the Wi-Fi card leave the network's channel every few seconds to look for nearby Apple
/// devices. Measured with a Galaxy Tab A7 at 60 fps: the Mac handed every frame to its socket on
/// time, and the tablet still saw a 150 ms hole every 14 seconds, followed by a burst of late
/// frames. Taking `awdl0` down is the only way to keep the radio on one channel.
///
/// It needs root, so every change goes through the system's own administrator prompt. The state
/// is always read from the interface rather than remembered: macOS brings AWDL back on its own
/// whenever something asks for it, such as opening AirDrop.
enum AirDropPause {
    private static let interface = "awdl0"

    /// True when `awdl0` exists and is down. A Mac without the interface has nothing to pause.
    static var isPaused: Bool {
        guard let flags = interfaceFlags() else { return false }
        return flags & UInt32(IFF_UP) == 0
    }

    static var isAvailable: Bool { interfaceFlags() != nil }

    /// Returns the state actually in force afterwards. Cancelling the password prompt leaves it as it was.
    @discardableResult
    static func set(paused: Bool) -> Bool {
        let command = "/sbin/ifconfig \(interface) \(paused ? "down" : "up")"
        var error: NSDictionary?
        NSAppleScript(source: "do shell script \"\(command)\" with administrator privileges")?
            .executeAndReturnError(&error)
        if let error {
            Diagnostics.shared.displayLog.notice("AWDL \(paused ? "pause" : "resume", privacy: .public) not applied: \(error, privacy: .public)")
        }
        return isPaused
    }

    private static func interfaceFlags() -> UInt32? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let first else { return nil }
        defer { freeifaddrs(first) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            if String(cString: entry.pointee.ifa_name) == interface {
                return entry.pointee.ifa_flags
            }
        }
        return nil
    }
}
