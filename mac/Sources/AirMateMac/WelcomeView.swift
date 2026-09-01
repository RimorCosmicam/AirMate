import SwiftUI

/// The first run, as one card.
///
/// What AirMate needs, then the tablet, then out of the way. There is no header saying which panel
/// this is and no progress dots: the list is short enough to read, and `ALL DONE` ending it is the
/// same shape every other commitment in the language takes. Mustard stripes behind it — this is
/// the moment that colour exists for.
struct WelcomeView: View {
    @ObservedObject var model: AppModel

    private var granted: Bool { model.permissionGranted }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MontWordmark()
                .padding(.bottom, 4)
            MontDetail("A second screen for your Mac, on the tablet you already own.")
                .padding(.bottom, 18)

            requirement(
                label: "Screen recording",
                detail: "AirMate captures only its own virtual display, never your real one.",
                satisfied: granted,
                actionLabel: granted ? "Granted" : "Allow"
            ) {
                model.onOpenPermissionSettings()
            }

            if case .permissionRequired(true) = model.state {
                MontRow(label: "Restart AirMate", trailing: "Needed") {
                    model.onRestartForPermission()
                }
            }

            requirement(
                label: "The tablet",
                detail: "Install AirMate on it and join the same Wi‑Fi. Nothing else to set up.",
                satisfied: false,
                actionLabel: "Save app…"
            ) {
                model.onSaveAndroidApp()
            }

            Spacer(minLength: 14)

            // Dim until there is nothing left to grant, so it reads as the end of the list
            // rather than a way past it.
            MontRow(label: "All done", enabled: granted) { model.completeWelcome() }
        }
    }

    @ViewBuilder
    private func requirement(
        label: String,
        detail: String,
        satisfied: Bool,
        actionLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            MontRow(
                label: label,
                trailing: actionLabel,
                emphasis: satisfied ? MontWhite.dim : MontWhite.active,
                action: action
            )
            MontDetail(detail)
        }
        .padding(.bottom, 9)
    }
}
