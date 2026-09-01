import AppKit
import CoreImage
import SwiftUI

/// The AirMate window.
///
/// Black, square, and set almost entirely in Mont Black. There are no buttons drawn here: a row is
/// a word that does something, bright when it can and dim when it cannot, which is the same rule
/// the Android client's card follows. Stripes appear only while pairing — Mont keeps them off
/// anything you have to read.
struct MontWindowView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ZStack {
            Color.black

            VStack(alignment: .leading, spacing: 0) {
                if model.welcomeCompleted {
                    content
                } else {
                    WelcomeView(model: model)
                }
            }
            .padding(.leading, 22)
            .padding(.trailing, 14)
            .padding(.top, 30)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Color.black)
        }
        .frame(minWidth: 380, minHeight: 260)
    }

    @ViewBuilder
    private var content: some View {
        MontWordmark()
            .padding(.bottom, 16)

        switch model.state {
        case let .permissionRequired(restartReady):
            permission(restartReady: restartReady)
        case .starting:
            MontDetail("Preparing the wireless second screen…")
        case let .waitingForAndroid(url):
            pairing(url: url)
        case .connectingVideo:
            MontDetail("Android found. Starting the video stream…")
            MontRow(label: "Stop display") { model.onToggleDisplay() }
            MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
        case let .connected(snapshot, configuration):
            connected(snapshot: snapshot, configuration: configuration)
        case let .stopped(configuration):
            MontDetail("Start AirMate when you want to reconnect.")
            settings(configuration)
            MontRow(label: "Start display") { model.onToggleDisplay() }
            MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
        case let .failed(message):
            MontDetail(message)
            MontRow(label: "Try again") { model.onToggleDisplay() }
            MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
        }

        Spacer(minLength: 12)
        MontRow(label: "Close") { model.onClose() }
    }

    @ViewBuilder
    private func permission(restartReady: Bool) -> some View {
        MontDetail(
            restartReady
                ? "After enabling AirMate in Settings, restart it to apply access."
                : "Open Settings and Finder, then drag the selected AirMate app into Screen Recording."
        )
        if restartReady {
            MontRow(label: "Restart AirMate") { model.onRestartForPermission() }
            MontRow(label: "Open settings again", emphasis: MontWhite.dim) {
                model.onOpenPermissionSettings()
            }
        } else {
            MontRow(label: "Open settings & show AirMate") { model.onOpenPermissionSettings() }
        }
    }

    @ViewBuilder
    private func pairing(url: String?) -> some View {
        MontDetail("Open AirMate on the tablet. Same Wi‑Fi is enough; the code is a shortcut.")
        if let url, let image = MontWindowView.qrCode(url) {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .frame(width: 132, height: 132)
                .padding(.vertical, 10)
        }
        MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
        MontRow(label: "Stop display", emphasis: MontWhite.dim) { model.onToggleDisplay() }
    }

    @ViewBuilder
    private func connected(snapshot: StreamSnapshot, configuration: DisplayConfiguration) -> some View {
        MontDetail("AirMate Display is active as your second monitor.")
        HStack(alignment: .top, spacing: 26) {
            MontMetric(
                title: "RESOLUTION",
                value: configuration.resolutionLabel + (configuration.hiDPI ? " HiDPI" : "")
            )
            MontMetric(title: "REFRESH", value: "60 Hz")
            MontMetric(title: "CODEC", value: "HEVC")
            MontMetric(title: "FRAMES", value: snapshot.encoded.formatted())
        }
        .padding(.vertical, 12)
        settings(configuration)
        MontRow(label: "Stop display") { model.onToggleDisplay() }
        MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
    }

    @ViewBuilder
    private func settings(_ configuration: DisplayConfiguration) -> some View {
        HStack(spacing: 14) {
            ForEach(DisplayConfiguration.resolutions, id: \.0) { resolution in
                MontChip(
                    label: "\(resolution.0) × \(resolution.1)",
                    selected: resolution.0 == configuration.width && resolution.1 == configuration.height
                ) {
                    model.onConfigurationChanged(
                        DisplayConfiguration(
                            width: resolution.0,
                            height: resolution.1,
                            hiDPI: configuration.hiDPI
                        )
                    )
                }
            }
        }
        HStack(spacing: 10) {
            Text("TOUCH CONTROL")
                .font(.montBlack(11))
                .foregroundStyle(.white.opacity(MontWhite.dim))
            Spacer()
            // A grant, not a switch: macOS decides this one, and pretending otherwise would make
            // the control lie whenever the user revokes it in Settings.
            MontRow(
                label: model.pointerPermitted ? "Granted" : "Allow…",
                enabled: !model.pointerPermitted,
                emphasis: model.pointerPermitted ? MontWhite.dim : MontWhite.active
            ) {
                model.onRequestPointerPermission()
            }
            .fixedSize()
        }
        if !model.pointerPermitted {
            MontDetail("Taps and scrolls from the tablet need Accessibility. Without it they do nothing at all.")
        }
        HStack(spacing: 10) {
            Text("START WITH MAC")
                .font(.montBlack(11))
                .foregroundStyle(.white.opacity(MontWhite.dim))
            MontToggle(isOn: model.launchAtLogin) { model.onLaunchAtLogin($0) }
        }
        .padding(.top, 4)
        HStack(spacing: 10) {
            Text("HIDPI")
                .font(.montBlack(11))
                .foregroundStyle(.white.opacity(MontWhite.dim))
            MontToggle(isOn: configuration.hiDPI) { hiDPI in
                model.onConfigurationChanged(
                    DisplayConfiguration(
                        width: configuration.width,
                        height: configuration.height,
                        hiDPI: hiDPI
                    )
                )
            }
        }
        .padding(.vertical, 8)
    }

    static func qrCode(_ value: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else {
            return nil
        }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}
