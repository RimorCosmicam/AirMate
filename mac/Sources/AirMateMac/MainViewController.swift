import AppKit

final class MainViewController: NSViewController {
    var onToggleDisplay: (() -> Void)?

    private let statusDot = NSTextField(labelWithString: "●")
    private let statusTitle = NSTextField(labelWithString: "Ready")
    private let statusDetail = NSTextField(labelWithString: "Start the virtual display, then open AirMate on your Android tablet.")
    private let actionButton = NSButton(title: "Start Display", target: nil, action: nil)
    private let framesLabel = NSTextField(labelWithString: "0 frames encoded")
    private var displayRunning = false

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 640, height: 430))
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

        let content = NSStackView()
        content.orientation = .vertical
        content.alignment = .leading
        content.spacing = 24
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)

        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            content.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
            content.topAnchor.constraint(equalTo: view.topAnchor, constant: 30),
            content.bottomAnchor.constraint(lessThanOrEqualTo: view.bottomAnchor, constant: -24)
        ])

        content.addArrangedSubview(makeHeader())
        content.addArrangedSubview(makeConnectionCard())
        content.addArrangedSubview(makeModeCard())

        let footer = NSTextField(labelWithString: "Development preview  •  Use on a trusted local network")
        footer.font = .systemFont(ofSize: 11)
        footer.textColor = .tertiaryLabelColor
        content.addArrangedSubview(footer)
    }

    private func makeHeader() -> NSView {
        let icon = NSImageView(image: NSApplication.shared.applicationIconImage)
        icon.imageScaling = .scaleProportionallyUpOrDown
        icon.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            icon.widthAnchor.constraint(equalToConstant: 64),
            icon.heightAnchor.constraint(equalToConstant: 64)
        ])

        let title = NSTextField(labelWithString: "AirMate")
        title.font = .systemFont(ofSize: 26, weight: .semibold)
        let subtitle = NSTextField(labelWithString: "Turn your Android tablet into a second Mac display.")
        subtitle.font = .systemFont(ofSize: 14)
        subtitle.textColor = .secondaryLabelColor

        let labels = NSStackView(views: [title, subtitle])
        labels.orientation = .vertical
        labels.alignment = .leading
        labels.spacing = 5

        let header = NSStackView(views: [icon, labels])
        header.orientation = .horizontal
        header.alignment = .centerY
        header.spacing = 16
        return header
    }

    private func makeConnectionCard() -> NSView {
        statusDot.font = .systemFont(ofSize: 14, weight: .bold)
        statusDot.textColor = .secondaryLabelColor
        statusTitle.font = .systemFont(ofSize: 16, weight: .semibold)
        statusDetail.font = .systemFont(ofSize: 13)
        statusDetail.textColor = .secondaryLabelColor
        statusDetail.maximumNumberOfLines = 2
        statusDetail.lineBreakMode = .byWordWrapping

        let statusRow = NSStackView(views: [statusDot, statusTitle])
        statusRow.orientation = .horizontal
        statusRow.alignment = .centerY
        statusRow.spacing = 8

        let copy = NSStackView(views: [statusRow, statusDetail])
        copy.orientation = .vertical
        copy.alignment = .leading
        copy.spacing = 6

        actionButton.target = self
        actionButton.action = #selector(toggleDisplay)
        actionButton.bezelStyle = .rounded
        actionButton.controlSize = .large
        actionButton.keyEquivalent = "\r"
        actionButton.translatesAutoresizingMaskIntoConstraints = false
        actionButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 132).isActive = true

        let row = NSStackView(views: [copy, actionButton])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.distribution = .fill
        row.spacing = 20
        row.translatesAutoresizingMaskIntoConstraints = false

        let card = makeCard()
        card.addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            row.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            row.topAnchor.constraint(equalTo: card.topAnchor, constant: 18),
            row.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -18),
            card.widthAnchor.constraint(equalToConstant: 576)
        ])
        return card
    }

    private func makeModeCard() -> NSView {
        let mode = metric(title: "RESOLUTION", value: "1920 × 1080")
        let refresh = metric(title: "REFRESH", value: "60 Hz")
        let codec = metric(title: "CODEC", value: "HEVC")
        framesLabel.font = .systemFont(ofSize: 12)
        framesLabel.textColor = .secondaryLabelColor

        let metrics = NSStackView(views: [mode, refresh, codec])
        metrics.orientation = .horizontal
        metrics.alignment = .top
        metrics.distribution = .fillEqually
        metrics.spacing = 24

        let stack = NSStackView(views: [metrics, framesLabel])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false

        let card = makeCard()
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 17),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -17),
            card.widthAnchor.constraint(equalToConstant: 576)
        ])
        return card
    }

    private func metric(title: String, value: String) -> NSView {
        let heading = NSTextField(labelWithString: title)
        heading.font = .systemFont(ofSize: 10, weight: .semibold)
        heading.textColor = .tertiaryLabelColor
        let valueLabel = NSTextField(labelWithString: value)
        valueLabel.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        let stack = NSStackView(views: [heading, valueLabel])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 4
        return stack
    }

    private func makeCard() -> NSView {
        let card = NSView()
        card.wantsLayer = true
        card.layer?.cornerRadius = 14
        card.layer?.backgroundColor = NSColor.controlBackgroundColor.cgColor
        return card
    }

    @objc private func toggleDisplay() { onToggleDisplay?() }

    func update(displayRunning: Bool, snapshot: StreamSnapshot) {
        self.displayRunning = displayRunning
        if displayRunning {
            statusDot.textColor = .systemGreen
            statusTitle.stringValue = "Display active"
            statusDetail.stringValue = "AirMate Display is available in macOS Display Settings. Waiting for your tablet."
            actionButton.title = "Stop Display"
        } else {
            statusDot.textColor = .secondaryLabelColor
            statusTitle.stringValue = "Ready"
            statusDetail.stringValue = "Start the virtual display, then open AirMate on your Android tablet."
            actionButton.title = "Start Display"
        }
        framesLabel.stringValue = "\(snapshot.encoded) frames encoded  •  \(snapshot.droppedPending + snapshot.droppedNetwork) dropped"
    }
}

