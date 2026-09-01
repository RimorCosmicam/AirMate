import SwiftUI

struct DisplayConfiguration: Equatable, Sendable {
    let width: Int
    let height: Int
    /// Always off, and there is no control for it.
    ///
    /// HiDPI halves the desktop: a display built at 1800 x 1080 pixels presents as 900 x 540
    /// points, so everything is drawn at double size and a toolbar ends up wider than a finger.
    /// It would only make sense if the display were built at twice the size wanted, and twice
    /// these sizes is past what the client can decode.
    var hiDPI: Bool = false

    /// Fallback sizes, used only until the client says how big it is.
    static let resolutions = [(1280, 800), (1920, 1080), (1920, 1200)]

    /// Five sizes at the client's own shape, evenly spaced from all of it down to half.
    ///
    /// The same derivation the client makes, so the two windows offer the same list rather than
    /// each proposing shapes the other does not recognise. Sides are rounded to even numbers,
    /// which every encoder wants and which moves the shape by less than a pixel.
    static func choices(
        fitting panel: (width: Int, height: Int)?,
        within ceiling: (width: Int, height: Int)? = nil
    ) -> [(Int, Int)] {
        guard let panel, panel.width > 0, panel.height > 0 else { return resolutions }
        // Multiples of sixteen: hardware decoders refuse anything else, however far inside their
        // stated limits it is.
        func even(_ value: Double) -> Int { max(16, Int((value / 16).rounded()) * 16) }
        let derived = [1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4].map { scale in
            (even(Double(panel.width) * scale), even(Double(panel.height) * scale))
        }.filter { candidate in
            guard candidate.0 >= 640, candidate.1 >= 480 else { return false }
            // Never above what the client can decode. That is not a worse picture, it is none.
            guard let ceiling, ceiling.width > 0, ceiling.height > 0 else { return true }
            return candidate.0 <= ceiling.width && candidate.1 <= ceiling.height
        }
        // Four, matching what the client offers, so the two windows never disagree.
        return derived.isEmpty ? resolutions : Array(derived.prefix(4))
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

    /// The largest frame the client's decoder will accept, once it has said.
    ///
    /// Beyond it there is no picture at all — the decoder answers with a hardware error and dies —
    /// so the host must never offer a size above it.
    @Published var clientCeiling: (width: Int, height: Int)?

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
        DisplayConfiguration.choices(fitting: clientDisplay, within: clientCeiling)
    }

    var pairingURL: String? {
        if case let .waitingForAndroid(url) = state { return url }
        return nil
    }
}
