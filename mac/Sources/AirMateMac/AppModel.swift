import SwiftUI

struct DisplayConfiguration: Equatable, Sendable {
    let width: Int
    let height: Int
    var hiDPI: Bool

    /// Fallback sizes, used only until the client says how big it is.
    static let resolutions = [(1280, 800), (1920, 1080), (1920, 1200)]

    /// Five sizes at the client's own shape, evenly spaced from all of it down to half.
    ///
    /// The same derivation the client makes, so the two windows offer the same list rather than
    /// each proposing shapes the other does not recognise. Sides are rounded to even numbers,
    /// which every encoder wants and which moves the shape by less than a pixel.
    static func choices(fitting panel: (width: Int, height: Int)?) -> [(Int, Int)] {
        guard let panel, panel.width > 0, panel.height > 0 else { return resolutions }
        func even(_ value: Double) -> Int { Int((value / 2).rounded()) * 2 }
        let derived = [1.0, 0.875, 0.75, 0.625, 0.5].map { scale in
            (even(Double(panel.width) * scale), even(Double(panel.height) * scale))
        }.filter { $0.0 >= 640 && $0.1 >= 480 }
        return derived.isEmpty ? resolutions : derived
    }
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

    /// Whether this Mac will accept taps and scrolls from the tablet.
    ///
    /// Accessibility, not Screen Recording — a separate grant, and one that fails silently when it
    /// is missing, so the window has to say so rather than letting taps quietly do nothing.
    @Published var pointerPermitted = false

    /// The tablet's own panel size, once it has said. Nil until then.
    @Published var clientDisplay: (width: Int, height: Int)?

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

    /// The sizes this window offers, tailored to the client once it has said how big it is.
    var resolutionChoices: [(Int, Int)] {
        DisplayConfiguration.choices(fitting: clientDisplay)
    }

    var pairingURL: String? {
        if case let .waitingForAndroid(url) = state { return url }
        return nil
    }
}
