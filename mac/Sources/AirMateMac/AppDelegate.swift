import AppKit
import CoreGraphics
import SwiftUI

@main
@MainActor
enum AirMateMain {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.run()
        withExtendedLifetime(delegate) {}
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let model = AppModel()
    private var statusItem: NSStatusItem!
    private var window: NSWindow?
    private var display: VirtualDisplayBackend?
    private var capture: DisplayCapture?
    private var encoder: LatestFrameEncoder?
    private var sender: UDPSender?
    private var diagnosticsTimer: Timer?
    private var wantsDisplayRunning = true
    private var startingDisplay = false
    private var permissionSettingsOpened = false
    private var lastError: String?

    func applicationDidFinishLaunching(_ notification: Notification) {
        MontFont.register()
        if let icon = Bundle.main.image(forResource: "AppIcon") {
            NSApp.applicationIconImage = icon
        }
        ApplicationMenu.install(target: self)
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            button.toolTip = "AirMate wireless display"
            button.image = menuBarIcon()
        }

        model.onToggleDisplay = { [weak self] in
            guard let self else { return }
            self.display == nil ? self.startDisplay() : self.stopDisplay()
        }
        model.onOpenPermissionSettings = { [weak self] in self?.openPermissionSettings() }
        model.onRestartForPermission = { [weak self] in self?.restartForPermission() }
        model.onSaveAndroidApp = { [weak self] in self?.saveAndroidApp() }
        model.onConfigurationChanged = { [weak self] in self?.applyConfiguration($0) }
        model.onControlDecision = { [weak self] allow in self?.resolveControlRequest(allow) }
        model.onLaunchAtLogin = { [weak self] wanted in
            // Read the state back: a login item the user has denied in System Settings stays
            // denied, and a switch that lies about that is worse than no switch.
            self?.model.launchAtLogin = LoginItem.set(wanted)
        }
        model.onClose = { [weak self] in self?.window?.performClose(nil) }

        diagnosticsTimer = Timer.scheduledTimer(
            timeInterval: 1,
            target: self,
            selector: #selector(refreshUI),
            userInfo: nil,
            repeats: true
        )
        DispatchQueue.main.async { [weak self] in self?.openWindow() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// A drawn mark rather than an SF Symbol: Mont's own shapes, and it matches the app icon's
    /// screen rather than borrowing a system glyph.
    private func menuBarIcon() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { _ in
            NSColor.labelColor.setStroke()
            let screen = NSBezierPath(rect: NSRect(x: 2.5, y: 4.5, width: 13, height: 9))
            screen.lineWidth = 1.6
            screen.stroke()
            return true
        }
        image.isTemplate = true
        return image
    }

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: "Open AirMate", action: #selector(openWindow), keyEquivalent: "o").target = self
        menu.addItem(.separator())
        if display == nil {
            menu.addItem(withTitle: "Start Display", action: #selector(startDisplay), keyEquivalent: "s").target = self
        } else {
            menu.addItem(withTitle: "Stop Display", action: #selector(stopDisplay), keyEquivalent: "s").target = self
        }
        let snapshot = Diagnostics.shared.snapshot()
        let connected = clientIsConnected(snapshot)
        let statusTitle = display == nil ? "Display Off" : (connected ? "Android Connected" : "Waiting for Android")
        let status = NSMenuItem(title: statusTitle, action: nil, keyEquivalent: "")
        status.isEnabled = false
        menu.addItem(status)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit AirMate", action: #selector(quit), keyEquivalent: "q").target = self
        statusItem?.menu = menu
    }

    @objc private func refreshUI() {
        rebuildMenu()

        guard CGPreflightScreenCaptureAccess() else {
            model.state = .permissionRequired(restartReady: permissionSettingsOpened)
            publishStatus()
            return
        }
        if wantsDisplayRunning && display == nil && !startingDisplay && lastError == nil {
            startDisplay()
            return
        }
        if startingDisplay {
            model.state = .starting
        } else if let lastError {
            model.state = .failed(lastError)
        } else if display == nil {
            model.state = .stopped(configuration: model.configuration)
        } else {
            let snapshot = Diagnostics.shared.snapshot()
            if clientIsConnected(snapshot) {
                model.state = snapshot.encoded == 0
                    ? .connectingVideo
                    : .connected(snapshot: snapshot, configuration: model.configuration)
            } else {
                model.state = .waitingForAndroid(pairingURL: PairingAddress.url(port: 48620))
            }
        }
        publishStatus()
    }

    /// Tell the tablet what this Mac is doing, so its control card can describe a display that is
    /// stopped and sending no video.
    private func publishStatus() {
        sender?.sendStatus(
            running: display != nil,
            hiDPI: model.configuration.hiDPI,
            width: model.configuration.width,
            height: model.configuration.height,
            encodedFrames: Diagnostics.shared.snapshot().encoded
        )
    }

    private func clientIsConnected(_ snapshot: StreamSnapshot) -> Bool {
        guard snapshot.lastClientHelloNanos > 0 else { return false }
        return DispatchTime.now().uptimeNanoseconds - snapshot.lastClientHelloNanos < 3_000_000_000
    }

    @objc private func openWindow() {
        if window == nil {
            let created = NSWindow(contentViewController: NSHostingController(rootView: MontWindowView(model: model)))
            created.title = "AirMate"
            created.titleVisibility = .hidden
            created.delegate = self
            // Closable stays in the mask so Cmd+W has something to act on; the buttons themselves
            // are hidden, because the window says Close in words like every other Mont surface.
            created.styleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
            created.titlebarAppearsTransparent = true
            created.isMovableByWindowBackground = true
            created.backgroundColor = .black
            created.standardWindowButton(.closeButton)?.isHidden = true
            created.standardWindowButton(.miniaturizeButton)?.isHidden = true
            created.standardWindowButton(.zoomButton)?.isHidden = true
            created.setContentSize(NSSize(width: 420, height: 380))
            created.center()
            created.isReleasedWhenClosed = false
            window = created
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        refreshUI()
    }

    func windowWillClose(_ notification: Notification) {
        window = nil
        DispatchQueue.main.async { NSApp.setActivationPolicy(.accessory) }
    }

    @objc private func startDisplay() {
        wantsDisplayRunning = true
        lastError = nil
        guard CGPreflightScreenCaptureAccess(), display == nil, !startingDisplay else {
            refreshUI()
            return
        }
        startingDisplay = true
        Diagnostics.shared.mutate {
            $0.lastClientHelloNanos = 0
            $0.captured = 0
            $0.submitted = 0
            $0.encoded = 0
        }
        refreshUI()

        do {
            let sender = try UDPSender()
            sender.onCommand = { [weak self] command in self?.perform(command) }
            sender.onControlRequest = { [weak self] address in self?.model.controlRequest = address }
            // A client that arrives mid-GOP has nothing it can decode until the next keyframe, and
            // there is no retransmission for it to ask with. Give it one immediately.
            sender.onClientChanged = { [weak self] in self?.encoder?.requestKeyframe() }
            let display = try CoreGraphicsVirtualDisplayBackend(
                width: UInt32(model.configuration.width),
                height: UInt32(model.configuration.height),
                hiDPI: model.configuration.hiDPI
            )
            let encoder = try LatestFrameEncoder(
                width: Int32(model.configuration.width),
                height: Int32(model.configuration.height),
                sender: sender
            )
            let capture = DisplayCapture(
                displayID: display.displayID,
                width: model.configuration.width,
                height: model.configuration.height,
                encoder: encoder
            )
            self.sender = sender
            self.display = display
            self.encoder = encoder
            self.capture = capture

            Task { @MainActor [weak self] in
                do {
                    try await capture.start()
                    guard self?.capture === capture else { return }
                    self?.startingDisplay = false
                    Diagnostics.shared.displayLog.info("AirMate Display started with ID \(display.displayID)")
                    self?.refreshUI()
                } catch {
                    guard self?.capture === capture else { return }
                    self?.lastError = error.localizedDescription
                    self?.tearDownDisplay()
                    self?.refreshUI()
                }
            }
        } catch {
            lastError = error.localizedDescription
            tearDownDisplay()
            refreshUI()
        }
    }

    @objc private func stopDisplay() {
        wantsDisplayRunning = false
        lastError = nil
        tearDownDisplay()
        refreshUI()
    }

    private func tearDownDisplay() {
        startingDisplay = false
        capture?.stop()
        capture = nil
        encoder = nil
        sender?.close()
        sender = nil
        display?.stop()
        display = nil
        model.controlRequest = nil
        Diagnostics.shared.mutate { $0.lastClientHelloNanos = 0 }
    }

    /// A command from the tablet, already checked against the authorised address.
    private func perform(_ command: ControlPacket.Command) {
        switch command {
        case .hello:
            break
        case .start:
            startDisplay()
        case .stop:
            stopDisplay()
        case let .setDisplay(width, height, hiDPI):
            applyConfiguration(DisplayConfiguration(width: width, height: height, hiDPI: hiDPI))
        case .requestIDR:
            encoder?.requestKeyframe()
        }
    }

    private func resolveControlRequest(_ allow: Bool) {
        sender?.resolveControlRequest(allow: allow)
        model.controlRequest = nil
        if allow { Diagnostics.shared.networkLog.info("Control authorised by the user") }
    }

    private func applyConfiguration(_ newConfiguration: DisplayConfiguration) {
        guard newConfiguration != model.configuration else { return }
        model.configuration = newConfiguration
        lastError = nil
        if display != nil || startingDisplay {
            tearDownDisplay()
            startDisplay()
        } else {
            refreshUI()
        }
    }

    private func openPermissionSettings() {
        permissionSettingsOpened = true
        refreshUI()
        if let settingsURL = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(settingsURL)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            NSWorkspace.shared.activateFileViewerSelecting([Bundle.main.bundleURL])
        }
    }

    private func restartForPermission() {
        let launchConfiguration = NSWorkspace.OpenConfiguration()
        launchConfiguration.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(
            at: Bundle.main.bundleURL,
            configuration: launchConfiguration
        ) { _, error in
            DispatchQueue.main.async {
                if let error {
                    NSAlert(error: error).runModal()
                } else {
                    NSApp.terminate(nil)
                }
            }
        }
    }

    private func saveAndroidApp() {
        guard let source = Bundle.main.url(forResource: "AirMate", withExtension: "apk") else {
            lastError = "The Android installer is missing from this build."
            refreshUI()
            return
        }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "AirMate.apk"
        panel.canCreateDirectories = true
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        guard let window else { return }
        panel.beginSheetModal(for: window) { response in
            guard response == .OK, let destination = panel.url else { return }
            do {
                try Data(contentsOf: source).write(to: destination, options: .atomic)
                NSWorkspace.shared.activateFileViewerSelecting([destination])
            } catch {
                NSAlert(error: error).beginSheetModal(for: window)
            }
        }
    }

    @objc private func quit() {
        tearDownDisplay()
        NSApp.terminate(nil)
    }
}
