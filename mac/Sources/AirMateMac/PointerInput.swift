import AppKit
import CoreGraphics

/// Reading mode: taps and scrolls on the tablet, as a pointer on this Mac.
///
/// The pointer is borrowed rather than taken. A tap on the second screen moves the cursor there,
/// clicks, and puts it back exactly where it was — so the tablet behaves like a page you are
/// reading rather than a second mouse fighting the first. Without the restore, turning a page on
/// the tablet would drag the cursor off whatever you were doing.
///
/// Clicking and scrolling is the whole vocabulary, which is what a reader needs and no more.
@MainActor
enum PointerInput {
    /// Where the cursor was before the gesture in progress started.
    private static var restore: CGPoint?

    /// Synthesising events for other applications needs Accessibility, which is a separate grant
    /// from Screen Recording and is refused silently rather than with an error.
    static var isPermitted: Bool { AXIsProcessTrusted() }

    @discardableResult
    static func requestPermission() -> Bool {
        // The constant is a global var, which strict concurrency will not let us touch; the key
        // itself is documented and stable.
        let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    static func click(x: UInt16, y: UInt16, on displayID: CGDirectDisplayID) {
        guard isPermitted, let target = point(x: x, y: y, on: displayID) else { return }
        let origin = cursor()
        warp(to: target)
        post(at: target, down: true)
        post(at: target, down: false)
        warp(to: origin)
    }

    static func scroll(
        phase: ControlPacket.ScrollPhase,
        x: UInt16,
        y: UInt16,
        dx: Int16,
        dy: Int16,
        on displayID: CGDirectDisplayID
    ) {
        guard isPermitted else { return }
        switch phase {
        case .begin:
            guard let target = point(x: x, y: y, on: displayID) else { return }
            // Saved once, at the start. Warping on every delta of a flick would make the cursor
            // strobe between two screens for the length of the gesture.
            restore = cursor()
            warp(to: target)
        case .continued:
            guard restore != nil else { return }
            wheel(dx: dx, dy: dy)
        case .ended:
            if dx != 0 || dy != 0 { wheel(dx: dx, dy: dy) }
            if let origin = restore { warp(to: origin) }
            restore = nil
        }
    }

    /// Drops any half-finished gesture, so a client that vanishes mid-scroll cannot strand the
    /// cursor on the tablet's display.
    static func reset() {
        if let origin = restore { warp(to: origin) }
        restore = nil
    }

    // MARK: - Plumbing

    private static func point(x: UInt16, y: UInt16, on displayID: CGDirectDisplayID) -> CGPoint? {
        let bounds = CGDisplayBounds(displayID)
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        // Normalised in, display coordinates out: the client never has to know whether this display
        // is HiDPI, which is the one thing it would reliably get wrong.
        return CGPoint(
            x: bounds.origin.x + bounds.width * CGFloat(x) / CGFloat(UInt16.max),
            y: bounds.origin.y + bounds.height * CGFloat(y) / CGFloat(UInt16.max)
        )
    }

    private static func cursor() -> CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    private static func warp(to point: CGPoint) {
        CGWarpMouseCursorPosition(point)
        // Warping breaks the tie between the hardware mouse and the cursor until this is called;
        // without it the user's own mouse stops moving the pointer.
        CGAssociateMouseAndMouseCursorPosition(1)
    }

    private static func post(at point: CGPoint, down: Bool) {
        CGEvent(
            mouseEventSource: nil,
            mouseType: down ? .leftMouseDown : .leftMouseUp,
            mouseCursorPosition: point,
            mouseButton: .left
        )?.post(tap: .cghidEventTap)
    }

    private static func wheel(dx: Int16, dy: Int16) {
        // Pixel units rather than lines, so a drag moves the page by the distance the finger moved.
        CGEvent(
            scrollWheelEvent2Source: nil,
            units: .pixel,
            wheelCount: 2,
            wheel1: Int32(dy),
            wheel2: Int32(dx),
            wheel3: 0
        )?.post(tap: .cghidEventTap)
    }
}
