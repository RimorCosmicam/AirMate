import SwiftUI

/// Interleaved diagonal bands, scrolling.
///
/// The one piece of ornament Mont allows, and only on full-screen moments — onboarding and
/// pairing — never behind content you have to read. Everything is drawn in a frame turned to the
/// bands' own slope, so within it they are plain horizontal rows and the geometry stays easy to
/// reason about. The same 26.565° and 34pt the Android client uses, so the two ends of the pairing
/// match while you are looking at both of them.
struct MontStripes: View {
    var first: Color
    var second: Color
    /// Seconds for the bands to travel one full period. Slow on purpose.
    var period: Double = 5.2

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in
                let spacing: CGFloat = 34
                let band = spacing / 2
                let span = size.width + size.height
                let elapsed = timeline.date.timeIntervalSinceReferenceDate
                let travel = CGFloat((elapsed / period).truncatingRemainder(dividingBy: 1))
                let drift = travel * spacing

                // SwiftUI rotates about the origin, so bring the centre there first.
                context.translateBy(x: size.width / 2, y: size.height / 2)
                context.rotate(by: .degrees(26.565))
                context.translateBy(x: -size.width / 2, y: -size.height / 2)

                var y = -span
                while y < size.height + span {
                    context.fill(
                        Path(CGRect(x: -span, y: y + drift, width: span * 3, height: band)),
                        with: .color(first)
                    )
                    context.fill(
                        Path(CGRect(x: -span, y: y + drift + band, width: span * 3, height: band)),
                        with: .color(second)
                    )
                    y += spacing
                }
            }
        }
        .ignoresSafeArea()
    }
}
