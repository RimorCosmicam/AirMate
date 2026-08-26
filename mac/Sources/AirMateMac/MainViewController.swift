import AppKit

final class MainViewController: NSViewController {
    var onToggleDisplay: (() -> Void)?

    private let statusDot = NSTextField(labelWithString: "●")
    private let statusTitle = NSTextField(labelWithString: "Ready")
    private let actionButton = NSButton(title: "Start Display", target: nil, action: nil)

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 230))

        let title = NSTextField(labelWithString: "AirMate")
        title.font = .systemFont(ofSize: 28, weight: .semibold)

        let content = NSStackView(views: [title, makePrimaryControl()])
        content.orientation = .vertical
        content.alignment = .leading
        content.spacing = 24
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)

        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 30),
            content.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -30),
            content.topAnchor.constraint(equalTo: view.topAnchor, constant: 28),
            content.bottomAnchor.constraint(lessThanOrEqualTo: view.bottomAnchor, constant: -28)
        ])
    }

    private func makePrimaryControl() -> NSView {
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

        let row = NSStackView(views: [status, actionButton])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.distribution = .fill
        row.spacing = 20
        row.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)

        let glass = NSGlassEffectView()
        glass.style = .regular
        glass.cornerRadius = 18
        glass.contentView = row
        glass.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            glass.widthAnchor.constraint(equalToConstant: 460),
            glass.heightAnchor.constraint(equalToConstant: 82)
        ])
        return glass
    }

    @objc private func toggleDisplay() { onToggleDisplay?() }

    func update(displayRunning: Bool, snapshot: StreamSnapshot) {
        statusDot.textColor = displayRunning ? .systemGreen : .secondaryLabelColor
        statusTitle.stringValue = displayRunning ? "Display Active" : "Ready"
        actionButton.title = displayRunning ? "Stop Display" : "Start Display"
    }
}

