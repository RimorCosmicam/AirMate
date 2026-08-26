import AppKit

final class MainViewController: NSViewController {
    var onToggleDisplay: (() -> Void)?

    private let statusDot = NSTextField(labelWithString: "●")
    private let statusTitle = NSTextField(labelWithString: "Ready")
    private let actionButton = NSButton(title: "Start Display", target: nil, action: nil)

    override func loadView() {
        statusDot.font = .systemFont(ofSize: 14, weight: .bold)
        statusDot.textColor = .secondaryLabelColor
        statusTitle.font = .systemFont(ofSize: 17, weight: .semibold)

        let status = NSStackView(views: [statusDot, statusTitle])
        status.orientation = .horizontal
        status.alignment = .centerY
        status.spacing = 9

        actionButton.target = self
        actionButton.action = #selector(toggleDisplay)
        actionButton.bezelStyle = .rounded
        actionButton.controlSize = .large
        actionButton.keyEquivalent = "\r"
        actionButton.translatesAutoresizingMaskIntoConstraints = false
        actionButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 132).isActive = true

        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        spacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let row = NSStackView(views: [status, spacer, actionButton])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.distribution = .fill
        row.spacing = 24
        row.edgeInsets = NSEdgeInsets(top: 48, left: 24, bottom: 20, right: 24)

        let glass = NSGlassEffectView()
        glass.style = .regular
        glass.cornerRadius = 0
        glass.contentView = row
        glass.frame = NSRect(x: 0, y: 0, width: 440, height: 130)
        view = glass
    }

    @objc private func toggleDisplay() { onToggleDisplay?() }

    func update(displayRunning: Bool) {
        statusDot.textColor = displayRunning ? .systemGreen : .secondaryLabelColor
        statusTitle.stringValue = displayRunning ? "Display Active" : "Ready"
        actionButton.title = displayRunning ? "Stop Display" : "Start Display"
    }
}
