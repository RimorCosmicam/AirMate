import AppKit
import CoreGraphics

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
    private var configuration = DisplayConfiguration(width: 1920, height: 1080, hiDPI: true)

    func applicationDidFinishLaunching(_ notification: Notification) {
        ApplicationMenu.install(target: self)
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            button.toolTip = "AirMate wireless display"
            if let icon = NSImage(systemSymbolName: "viewfinder", accessibilityDescription: "AirMate") {
                icon.isTemplate = true
                button.image = icon
            } else {
                button.image = fallbackMenuBarIcon()
            }
        }
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

    private func fallbackMenuBarIcon() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { _ in
            NSColor.labelColor.setStroke()
            let corners = NSBezierPath()
            corners.move(to: NSPoint(x: 7, y: 15)); corners.line(to: NSPoint(x: 3, y: 15)); corners.line(to: NSPoint(x: 3, y: 11))
            corners.move(to: NSPoint(x: 11, y: 15)); corners.line(to: NSPoint(x: 15, y: 15)); corners.line(to: NSPoint(x: 15, y: 11))
            corners.move(to: NSPoint(x: 7, y: 3)); corners.line(to: NSPoint(x: 3, y: 3)); corners.line(to: NSPoint(x: 3, y: 7))
            corners.move(to: NSPoint(x: 11, y: 3)); corners.line(to: NSPoint(x: 15, y: 3)); corners.line(to: NSPoint(x: 15, y: 7))
            corners.lineWidth = 1.7
            corners.lineCapStyle = .round
            corners.lineJoinStyle = .round
            corners.stroke()
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
        guard let controller = window?.contentViewController as? MainViewController else { return }

        guard CGPreflightScreenCaptureAccess() else {
            controller.render(.permissionRequired(restartReady: permissionSettingsOpened))
            return
        }
        if wantsDisplayRunning && display == nil && !startingDisplay && lastError == nil {
            startDisplay()
            return
        }
        if startingDisplay {
            controller.render(.starting)
        } else if let lastError {
            controller.render(.failed(lastError))
        } else if display == nil {
            controller.render(.stopped(configuration: configuration))
        } else {
            let snapshot = Diagnostics.shared.snapshot()
            if clientIsConnected(snapshot) {
                if snapshot.encoded == 0 {
                    controller.render(.connectingVideo)
                } else {
                    controller.render(.connected(snapshot: snapshot, configuration: configuration))
                }
            } else {
                controller.render(.waitingForAndroid(pairingURL: PairingAddress.url(port: 48620)))
            }
        }
    }

    private func clientIsConnected(_ snapshot: StreamSnapshot) -> Bool {
        guard snapshot.lastClientHelloNanos > 0 else { return false }
        return DispatchTime.now().uptimeNanoseconds - snapshot.lastClientHelloNanos < 3_000_000_000
    }

    @objc private func openWindow() {
        if window == nil {
            let viewController = MainViewController()
            viewController.onToggleDisplay = { [weak self] in
                guard let self else { return }
                self.display == nil ? self.startDisplay() : self.stopDisplay()
            }
            viewController.onOpenPermissionSettings = { [weak self] in self?.openPermissionSettings() }
            viewController.onRestartForPermission = { [weak self] in self?.restartForPermission() }
            viewController.onSaveAndroidApp = { [weak self] in self?.saveAndroidApp() }
            viewController.onConfigurationChanged = { [weak self] newConfiguration in
                self?.applyConfiguration(newConfiguration)
            }
            viewController.onPreferredHeightChanged = { [weak self] height in
                guard let window = self?.window, abs(window.contentLayoutRect.height - height) > 2 else { return }
                window.setContentSize(NSSize(width: 540, height: height))
            }

            let created = NSWindow(contentViewController: viewController)
            created.title = "AirMate"
            created.titleVisibility = .hidden
            created.delegate = self
            created.styleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
            created.titlebarAppearsTransparent = true
            created.backgroundColor = .clear
            created.isOpaque = false
            created.setContentSize(NSSize(width: 540, height: 300))
            created.minSize = NSSize(width: 520, height: 210)
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
            let display = try CoreGraphicsVirtualDisplayBackend(
                width: UInt32(configuration.width),
                height: UInt32(configuration.height),
                hiDPI: configuration.hiDPI
            )
            let encoder = try LatestFrameEncoder(
                width: Int32(configuration.width),
                height: Int32(configuration.height),
                sender: sender
            )
            let capture = DisplayCapture(
                displayID: display.displayID,
                width: configuration.width,
                height: configuration.height,
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
        sender = nil
        display?.stop()
        display = nil
        Diagnostics.shared.mutate { $0.lastClientHelloNanos = 0 }
    }

    private func applyConfiguration(_ newConfiguration: DisplayConfiguration) {
        guard newConfiguration != configuration else { return }
        configuration = newConfiguration
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
