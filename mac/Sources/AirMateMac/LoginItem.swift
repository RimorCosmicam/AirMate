import Foundation
import ServiceManagement

/// Whether AirMate starts with the Mac.
///
/// `SMAppService` registers the app itself as a login item, which the user can also see and revoke
/// in System Settings — so the switch here can disagree with reality, and the status is read back
/// rather than remembered. The older `LSSharedFileList` dance and a separate helper bundle both
/// exist to support systems this app does not target.
enum LoginItem {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    /// Returns the state actually in force afterwards, which is not always the one asked for:
    /// a user who has denied the login item in System Settings stays denied.
    @discardableResult
    static func set(_ enabled: Bool) -> Bool {
        do {
            if enabled {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } else if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            Diagnostics.shared.displayLog.error("Login item change failed: \(error.localizedDescription)")
        }
        return isEnabled
    }
}
