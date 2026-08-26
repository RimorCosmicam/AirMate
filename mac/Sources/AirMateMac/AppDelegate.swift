import AppKit

@main
enum AirMateMain {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.run()
        withExtendedLifetime(delegate) {}
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var statusItem: NSStatusItem!
    private var window: NSWindow?
    private var display: VirtualDisplayBackend?
    private var capture: DisplayCapture?
    private var encoder: LatestFrameEncoder?
    private var sender: UDPSender?
    private var diagnosticsTimer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            button.toolTip = "AirMate wireless display"
            if let icon = NSImage(systemSymbolName: "display", accessibilityDescription: "AirMate") {
                icon.isTemplate = true
                button.image = icon
            } else {
                button.image = fallbackMenuBarIcon()
            }
        }
        rebuildMenu()
        diagnosticsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.rebuildMenu() }
        // An app launched from Finder must always acknowledge the launch visibly.
        // Closing this window returns AirMate to menu-bar-only accessory mode.
        DispatchQueue.main.async { [weak self] in self?.openWindow() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    private func fallbackMenuBarIcon() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { _ in
            NSColor.labelColor.setStroke()
            let display = NSBezierPath(roundedRect: NSRect(x: 2, y: 4, width: 14, height: 10), xRadius: 2, yRadius: 2)
            display.lineWidth = 1.6
            display.stroke()
            let stand = NSBezierPath()
            stand.move(to: NSPoint(x: 9, y: 4))
            stand.line(to: NSPoint(x: 9, y: 2))
            stand.move(to: NSPoint(x: 6.5, y: 2))
            stand.line(to: NSPoint(x: 11.5, y: 2))
            stand.lineWidth = 1.6
            stand.stroke()
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
        let status = NSMenuItem(title: "Captured \(snapshot.captured) • Encoded \(snapshot.encoded) • Pending \(snapshot.pendingFrames)", action: nil, keyEquivalent: "")
        status.isEnabled = false
        menu.addItem(status)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit AirMate", action: #selector(quit), keyEquivalent: "q").target = self
        statusItem?.menu = menu
    }

    @objc private func openWindow() {
        if window == nil {
            let viewController = NSViewController()
            let label = NSTextField(labelWithString: "AirMate\n\nUse Display Settings to arrange AirMate Display.\nThe Android client listens on UDP port 48620.")
            label.alignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            viewController.view = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 260))
            viewController.view.addSubview(label)
            NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: viewController.view.centerXAnchor), label.centerYAnchor.constraint(equalTo: viewController.view.centerYAnchor)])
            let created = NSWindow(contentViewController: viewController)
            created.title = "AirMate"
            created.delegate = self
            created.setContentSize(NSSize(width: 520, height: 260))
            window = created
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        window = nil
        DispatchQueue.main.async { NSApp.setActivationPolicy(.accessory) }
    }

    @objc private func startDisplay() {
        do {
            let sender = try UDPSender()
            let display = try CoreGraphicsVirtualDisplayBackend()
            let encoder = try LatestFrameEncoder(width: 1920, height: 1080, sender: sender)
            let capture = try DisplayCapture(displayID: display.displayID, width: 1920, height: 1080, encoder: encoder)
            try capture.start()
            self.sender = sender; self.display = display; self.encoder = encoder; self.capture = capture
            Diagnostics.shared.displayLog.info("AirMate Display started with ID \(display.displayID)")
        } catch {
            let alert = NSAlert(error: error); alert.runModal()
            stopDisplay()
        }
        rebuildMenu()
    }

    @objc private func stopDisplay() {
        capture?.stop(); capture = nil; encoder = nil; sender = nil; display?.stop(); display = nil
        rebuildMenu()
    }

    @objc private func quit() { stopDisplay(); NSApp.terminate(nil) }
}
