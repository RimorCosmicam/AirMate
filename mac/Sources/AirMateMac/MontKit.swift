import SwiftUI

/// A row of the window. Text alone, bright when it can be used and dim when it cannot.
///
/// There is no box, pill or border here and there is not meant to be one: under Mont the type is
/// what makes a word act as a button, and adding a container back is the one thing the language
/// exists to do without. Labels are upper case.
struct MontRow: View {
    var label: String
    var trailing: String?
    var enabled = true
    var emphasis = MontWhite.active
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(label.uppercased())
                    .font(.montBlack(13))
                    .foregroundStyle(.white.opacity(enabled ? emphasis : MontWhite.disabled))
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let trailing {
                    Text(trailing.uppercased())
                        .font(.montBlack(11))
                        .foregroundStyle(.white.opacity(MontWhite.dim))
                }
            }
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// An explanatory line under a row.
struct MontDetail: View {
    var text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.montBlack(11))
            .foregroundStyle(.white.opacity(MontWhite.detail))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A choice. No pill, no border, no fill — selected is simply the bright one, which is the same
/// rule a row follows, because a choice *is* a row that happens to sit beside others.
struct MontChip: View {
    var label: String
    var selected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label.uppercased())
                .font(.montBlack(11))
                .foregroundStyle(.white.opacity(selected ? MontWhite.active : MontWhite.dim))
                .padding(.vertical, 4)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The slider stopped at two positions: a white block filling one half, and the state written in
/// the half it has left.
///
/// The word names what the control currently is, not what pressing it would do — a switch labelled
/// with its own opposite is a puzzle every single time you meet it.
struct MontToggle: View {
    var isOn: Bool
    var enabled = true
    var action: (Bool) -> Void

    private var alpha: Double { enabled ? MontWhite.active : MontWhite.disabled }

    var body: some View {
        Button { action(!isOn) } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                GeometryReader { proxy in
                    let half = proxy.size.width / 2
                    Rectangle().fill(.white.opacity(MontWhite.track * alpha))
                    Rectangle()
                        .fill(.white.opacity(alpha))
                        .frame(width: half)
                        .offset(x: isOn ? 0 : half)
                }
                Text(isOn ? "ON" : "OFF")
                    .font(.montBlack(10))
                    .foregroundStyle(.white.opacity(alpha))
                    .frame(width: 28)
            }
            .frame(width: 56, height: 18)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .animation(.easeOut(duration: 0.14), value: isOn)
    }
}

/// A measurement: a small dim label over the figure it names.
struct MontMetric: View {
    var title: String
    var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.montBlack(10))
                .foregroundStyle(.white.opacity(MontWhite.dim))
            Text(value)
                .font(.montBlack(13))
                .foregroundStyle(.white.opacity(MontWhite.primary))
                .monospacedDigit()
        }
    }
}

/// The wordmark: the lightest weight over the heaviest at one size. That contrast is the logo.
struct MontWordmark: View {
    var size: CGFloat = 34

    var body: some View {
        HStack(spacing: 0) {
            Text("air").font(.montThin(size))
            Text("Mate").font(.montBlack(size))
        }
        .foregroundStyle(.white)
    }
}
