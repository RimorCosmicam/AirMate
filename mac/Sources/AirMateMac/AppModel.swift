import SwiftUI

struct DisplayConfiguration: Equatable, Sendable {
    let width: Int
    let height: Int
    var hiDPI: Bool

    static let resolutions = [(1280, 800), (1920, 1080), (1920, 1200)]
    var resolutionLabel: String { "\(width) × \(height)" }
}

enum MainViewState: Equatable {
    case permissionRequired(restartReady: Bool)
    case starting
    case waitingForAndroid(pairingURL: String?)
    case connectingVideo
    case connected(snapshot: StreamSnapshot, configuration: DisplayConfiguration)
    case stopped(configuration: DisplayConfiguration)
    case failed(String)
}

/// What the window is looking at, and the handful of things it can ask for.
///
/// `AppDelegate` still owns the display, capture, encoder and sender; this only carries their
/// state across to SwiftUI and carries intentions back, the way the old view controller's
/// callbacks did.
@MainActor
final class AppModel: ObservableObject {
    @Published var state: MainViewState = .starting
    @Published var configuration = DisplayConfiguration(width: 1920, height: 1080, hiDPI: true)
    @Published var welcomeCompleted = UserDefaults.standard.bool(forKey: welcomeKey)
    @Published var launchAtLogin = LoginItem.isEnabled

    /// Whether this Mac will accept touches from the tablet as clicks and scrolls.
    ///
    /// Accessibility, not Screen Recording — a separate grant, and one that fails silently when it
    /// is missing, so the window has to say so rather than letting taps quietly do nothing.
    @Published var pointerPermitted = false

    var onToggleDisplay: () -> Void = {}
    var onOpenPermissionSettings: () -> Void = {}
    var onRestartForPermission: () -> Void = {}
    var onSaveAndroidApp: () -> Void = {}
    var onConfigurationChanged: (DisplayConfiguration) -> Void = { _ in }
    var onLaunchAtLogin: (Bool) -> Void = { _ in }
    var onRequestPointerPermission: () -> Void = {}
    var onClose: () -> Void = {}

    private static let welcomeKey = "AirMateWelcomeCompleted"

    func completeWelcome() {
        welcomeCompleted = true
        UserDefaults.standard.set(true, forKey: Self.welcomeKey)
    }

    var permissionGranted: Bool {
        if case .permissionRequired = state { return false }
        return true
    }

    var displayRunning: Bool {
        switch state {
        case .connected, .connectingVideo, .waitingForAndroid: return true
        default: return false
        }
    }

    var pairingURL: String? {
        if case let .waitingForAndroid(url) = state { return url }
        return nil
    }
}
