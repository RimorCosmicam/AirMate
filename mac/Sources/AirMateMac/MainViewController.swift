import AppKit
import CoreImage

struct DisplayConfiguration: Equatable, Sendable {
    let width: Int
    let height: Int
    var hiDPI: Bool

    static let resolutions = [(1280, 800), (1920, 1080), (1920, 1200)]
    var resolutionLabel: String { "\(width) × \(height)" }
}

enum MainViewState {
    case permissionRequired(restartReady: Bool)
    case starting
    case waitingForAndroid(pairingURL: String?)
    case connected(snapshot: StreamSnapshot, configuration: DisplayConfiguration)
    case stopped(configuration: DisplayConfiguration)
    case failed(String)
}

@MainActor
final class MainViewController: NSViewController {
    var onToggleDisplay: (() -> Void)?
    var onOpenPermissionSettings: (() -> Void)?
    var onRestartForPermission: (() -> Void)?
    var onSaveAndroidApp: (() -> Void)?
    var onConfigurationChanged: ((DisplayConfiguration) -> Void)?
    var onPreferredHeightChanged: ((CGFloat) -> Void)?

    private let sectionSymbol = NSImageView()
    private let sectionTitle = NSTextField(labelWithString: "")
    private let detailLabel = NSTextField(wrappingLabelWithString: "")
    private let qrImageView = NSImageView()
    private let qrCaption = NSTextField(labelWithString: "Scan with AirMate on Android")
    private let monitor = NSStackView()
    private let resolutionValue = NSTextField(labelWithString: "")
    private let refreshValue = NSTextField(labelWithString: "60 Hz")
    private let codecValue = NSTextField(labelWithString: "HEVC")
    private let framesValue = NSTextField(labelWithString: "0")
    private let settings = NSStackView()
    private let resolutionPicker = NSPopUpButton(frame: .zero, pullsDown: false)
    private let hiDPISwitch = NSSwitch(frame: .zero)
    private let primaryButton = NSButton(title: "", target: nil, action: nil)
    private let secondaryButton = NSButton(title: "", target: nil, action: nil)
    private var state = MainViewState.starting

    override func loadView() {
        let appName = NSTextField(labelWithString: "AirMate")
        appName.font = .systemFont(ofSize: 32, weight: .bold)

        sectionTitle.font = .systemFont(ofSize: 20, weight: .semibold)
        detailLabel.font = .systemFont(ofSize: 14)
        detailLabel.textColor = .secondaryLabelColor
        detailLabel.maximumNumberOfLines = 2

        sectionSymbol.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 23, weight: .medium)
        sectionSymbol.setContentHuggingPriority(.required, for: .horizontal)

        let sectionCopy = NSStackView(views: [sectionTitle, detailLabel])
        sectionCopy.orientation = .vertical
        sectionCopy.alignment = .leading
        sectionCopy.spacing = 5

        let helper = NSStackView(views: [sectionSymbol, sectionCopy])
        helper.orientation = .horizontal
        helper.alignment = .centerY
        helper.spacing = 14

        qrImageView.imageScaling = .scaleProportionallyUpOrDown
        qrImageView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            qrImageView.widthAnchor.constraint(equalToConstant: 154),
            qrImageView.heightAnchor.constraint(equalToConstant: 154)
        ])
        qrCaption.font = .systemFont(ofSize: 12, weight: .medium)
        qrCaption.textColor = .secondaryLabelColor
        let qrGroup = NSStackView(views: [qrImageView, qrCaption])
        qrGroup.orientation = .vertical
        qrGroup.alignment = .centerX
        qrGroup.spacing = 8
        qrGroup.identifier = NSUserInterfaceItemIdentifier("qrGroup")

        configureMonitor()
        configureSettings()

        primaryButton.target = self
        primaryButton.action = #selector(primaryAction)
        primaryButton.bezelStyle = .rounded
        primaryButton.controlSize = .large
        primaryButton.keyEquivalent = "\r"

        secondaryButton.target = self
        secondaryButton.action = #selector(secondaryAction)
        secondaryButton.bezelStyle = .rounded
        secondaryButton.controlSize = .large

        let actions = NSStackView(views: [primaryButton, secondaryButton])
        actions.orientation = .horizontal
        actions.alignment = .centerY
        actions.spacing = 10

        let content = NSStackView(views: [appName, helper, qrGroup, monitor, settings, actions])
        content.orientation = .vertical
        content.alignment = .leading
        content.spacing = 22
        content.edgeInsets = NSEdgeInsets(top: 52, left: 30, bottom: 26, right: 30)

        let glass = NSGlassEffectView()
        glass.style = .regular
        glass.cornerRadius = 0
        glass.contentView = content
        glass.frame = NSRect(x: 0, y: 0, width: 540, height: 300)
        view = glass
        render(.starting)
    }

    func render(_ newState: MainViewState) {
        state = newState
        let qrGroup = findView(identifier: "qrGroup")
        qrGroup?.isHidden = true
        monitor.isHidden = true
        settings.isHidden = true
        primaryButton.isHidden = false
        secondaryButton.isHidden = false
        sectionSymbol.contentTintColor = .secondaryLabelColor

        switch newState {
        case let .permissionRequired(restartReady):
            setSymbol("lock.shield")
            sectionTitle.stringValue = restartReady ? "Restart to finish" : "Allow Screen Recording"
            detailLabel.stringValue = restartReady
                ? "After adding and enabling AirMate in Settings, restart it to apply access."
                : "Open Settings and Finder, then drag the selected AirMate app into Screen Recording."
            primaryButton.title = restartReady ? "Restart AirMate" : "Open Settings & Show AirMate"
            secondaryButton.title = "Open Settings Again"
            secondaryButton.isHidden = !restartReady
            onPreferredHeightChanged?(245)
        case .starting:
            setSymbol("viewfinder")
            sectionTitle.stringValue = "Starting your display"
            detailLabel.stringValue = "Preparing the wireless second screen…"
            primaryButton.isHidden = true
            secondaryButton.isHidden = true
            onPreferredHeightChanged?(220)
        case let .waitingForAndroid(pairingURL):
            setSymbol("qrcode.viewfinder")
            sectionTitle.stringValue = "Pair your Android tablet"
            detailLabel.stringValue = "Install AirMate, then scan this code or simply open it on the same Wi‑Fi."
            qrImageView.image = pairingURL.flatMap(makeQRCode)
            qrGroup?.isHidden = qrImageView.image == nil
            primaryButton.title = "Save Android App…"
            secondaryButton.title = "Stop Display"
            onPreferredHeightChanged?(465)
        case let .connected(snapshot, configuration):
            setSymbol("checkmark.circle.fill")
            sectionSymbol.contentTintColor = .systemGreen
            sectionTitle.stringValue = "Android connected"
            detailLabel.stringValue = "AirMate Display is active as your second monitor."
            updateMonitor(snapshot: snapshot, configuration: configuration)
            updateSettings(configuration)
            monitor.isHidden = false
            settings.isHidden = false
            primaryButton.title = "Stop Display"
            secondaryButton.title = "Save Android App…"
            onPreferredHeightChanged?(405)
        case let .stopped(configuration):
            setSymbol("viewfinder")
            sectionTitle.stringValue = "Display is off"
            detailLabel.stringValue = "Start AirMate when you want to reconnect."
            updateSettings(configuration)
            settings.isHidden = false
            primaryButton.title = "Start Display"
            secondaryButton.title = "Save Android App…"
            onPreferredHeightChanged?(315)
        case let .failed(message):
            setSymbol("exclamationmark.triangle")
            sectionSymbol.contentTintColor = .systemOrange
            sectionTitle.stringValue = "AirMate couldn’t start"
            detailLabel.stringValue = message
            primaryButton.title = "Try Again"
            secondaryButton.title = "Save Android App…"
            onPreferredHeightChanged?(245)
        }
    }

    private func configureMonitor() {
        monitor.orientation = .horizontal
        monitor.alignment = .top
        monitor.spacing = 30
        monitor.addArrangedSubview(metric(title: "RESOLUTION", value: resolutionValue))
        monitor.addArrangedSubview(metric(title: "REFRESH", value: refreshValue))
        monitor.addArrangedSubview(metric(title: "CODEC", value: codecValue))
        monitor.addArrangedSubview(metric(title: "FRAMES", value: framesValue))
    }

    private func metric(title: String, value: NSTextField) -> NSView {
        let label = NSTextField(labelWithString: title)
        label.font = .systemFont(ofSize: 10, weight: .semibold)
        label.textColor = .tertiaryLabelColor
        value.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        let stack = NSStackView(views: [label, value])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        return stack
    }

    private func configureSettings() {
        resolutionPicker.addItems(withTitles: DisplayConfiguration.resolutions.map { "\($0.0) × \($0.1)" })
        resolutionPicker.target = self
        resolutionPicker.action = #selector(configurationChanged)
        resolutionPicker.controlSize = .regular

        hiDPISwitch.target = self
        hiDPISwitch.action = #selector(configurationChanged)
        let hiDPILabel = NSTextField(labelWithString: "HiDPI")
        hiDPILabel.font = .systemFont(ofSize: 13, weight: .medium)

        settings.orientation = .horizontal
        settings.alignment = .centerY
        settings.spacing = 10
        settings.addArrangedSubview(resolutionPicker)
        settings.addArrangedSubview(hiDPILabel)
        settings.addArrangedSubview(hiDPISwitch)
    }

    private func updateMonitor(snapshot: StreamSnapshot, configuration: DisplayConfiguration) {
        resolutionValue.stringValue = configuration.resolutionLabel + (configuration.hiDPI ? " HiDPI" : "")
        refreshValue.stringValue = "60 Hz"
        codecValue.stringValue = "HEVC"
        framesValue.stringValue = snapshot.encoded.formatted()
    }

    private func updateSettings(_ configuration: DisplayConfiguration) {
        if let index = DisplayConfiguration.resolutions.firstIndex(where: {
            $0.0 == configuration.width && $0.1 == configuration.height
        }) {
            resolutionPicker.selectItem(at: index)
        }
        hiDPISwitch.state = configuration.hiDPI ? .on : .off
    }

    private func findView(identifier: String) -> NSView? {
        view.subviewsRecursive.first { $0.identifier?.rawValue == identifier }
    }

    private func setSymbol(_ name: String) {
        sectionSymbol.image = NSImage(systemSymbolName: name, accessibilityDescription: nil)
    }

    private func makeQRCode(_ value: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else {
            return nil
        }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        image.isTemplate = false
        return image
    }

    @objc private func configurationChanged() {
        let index = max(0, resolutionPicker.indexOfSelectedItem)
        let resolution = DisplayConfiguration.resolutions[index]
        onConfigurationChanged?(DisplayConfiguration(
            width: resolution.0,
            height: resolution.1,
            hiDPI: hiDPISwitch.state == .on
        ))
    }

    @objc private func primaryAction() {
        switch state {
        case let .permissionRequired(restartReady):
            restartReady ? onRestartForPermission?() : onOpenPermissionSettings?()
        case .waitingForAndroid: onSaveAndroidApp?()
        case .connected, .stopped, .failed: onToggleDisplay?()
        case .starting: break
        }
    }

    @objc private func secondaryAction() {
        switch state {
        case .waitingForAndroid: onToggleDisplay?()
        case .connected, .stopped, .failed: onSaveAndroidApp?()
        case .permissionRequired(_): onOpenPermissionSettings?()
        case .starting: break
        }
    }
}

private extension NSView {
    var subviewsRecursive: [NSView] { subviews + subviews.flatMap(\.subviewsRecursive) }
}
