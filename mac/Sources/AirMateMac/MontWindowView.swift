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
            preferences
            MontRow(label: "Stop display") { model.onToggleDisplay() }
            MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
        case let .connected(snapshot, configuration):
            connected(snapshot: snapshot, configuration: configuration)
        case .stopped:
            MontDetail("Start AirMate when you want to reconnect.")
            preferences
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
    private func connected(snapshot _: StreamSnapshot, configuration: DisplayConfiguration) -> some View {
        MontDetail("AirMate Display is active as your second monitor.")
        // Nothing else. The tablet's own size is the tablet's business, the refresh rate and the
        // codec never change, and a running total of frames answers a question nobody asked. The
        // chips below say which size is running, which is the one fact worth showing.
        Spacer(minLength: 10)
        displaySettings(configuration)
        preferences
        MontRow(label: "Stop display") { model.onToggleDisplay() }
        MontRow(label: "Save Android app…") { model.onSaveAndroidApp() }
    }

    /// What the display is doing — meaningless until something is connected to show it on.
    ///
    /// Resolution and HiDPI both rebuild the display, and their whole effect is on a screen that
    /// is not there yet. Offering them to nobody invites changing a picture no one can see.
    @ViewBuilder
    private func displaySettings(_ configuration: DisplayConfiguration) -> some View {
        HStack(spacing: 14) {
            // Identified by position, not by width: 1920 × 1080 and 1920 × 1200 share a width, so
            // keying on it gave two chips the same identity and SwiftUI drew one of them twice.
            ForEach(Array(model.resolutionChoices.enumerated()), id: \.offset) { _, resolution in
                MontChip(
                    label: "\(resolution.0) × \(resolution.1)",
                    selected: resolution.0 == configuration.width && resolution.1 == configuration.height
                ) {
                    model.onConfigurationChanged(
                        DisplayConfiguration(
                            width: resolution.0,
                            height: resolution.1,
                            hiDPI: false
                        )
                    )
                }
            }
        }
        .padding(.bottom, 8)
    }

    /// What this Mac is doing, which is true whether or not a tablet is here yet.
    @ViewBuilder
    private var preferences: some View {
        // Only while there is something to do about it. A row reading GRANTED for the rest of the
        // app's life is a permanent reminder of a job already finished.
        if !model.pointerPermitted {
            HStack(spacing: 10) {
                Text("READING MODE")
                    .font(.montBlack(11))
                    .foregroundStyle(.white.opacity(MontWhite.dim))
                Spacer()
                MontRow(label: "Allow…") { model.onRequestPointerPermission() }
                    .fixedSize()
            }
            MontDetail("Tapping and scrolling from the tablet need Accessibility. Without it they do nothing at all.")
        }
        HStack(spacing: 10) {
            Text("START WITH MAC")
                .font(.montBlack(11))
                .foregroundStyle(.white.opacity(MontWhite.dim))
            MontToggle(isOn: model.launchAtLogin) { model.onLaunchAtLogin($0) }
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
