import AppKit
import CoreText
import SwiftUI

/// Mont, loaded from the bundle. The same typeface the Android client uses, so the two halves of
/// AirMate do not look like they were made by different people.
///
/// Only Thin and Black ship here. Thin does one job — the `air` of the wordmark — and Black is
/// everything else: under Mont it is the default weight rather than an emphasis weight, which is
/// what lets a plain word act as a button without a box around it.
enum MontFont {
    static let thin = "Mont-Thin"
    static let black = "Mont-Black"

    static func register() {
        for name in ["mont_thin", "mont_black"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else {
                Diagnostics.shared.displayLog.error("Mont \(name).ttf missing from the bundle")
                continue
            }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

extension Font {
    static func montBlack(_ size: CGFloat) -> Font { .custom(MontFont.black, size: size) }
    static func montThin(_ size: CGFloat) -> Font { .custom(MontFont.thin, size: size) }
}

/// White carries all the hierarchy, through opacity alone.
enum MontWhite {
    static let active = 1.0
    static let primary = 0.92
    static let detail = 0.62
    static let dim = 0.58
    static let disabled = 0.35
    static let track = 0.09
}

enum MontColor {
    /// The Mont surface: black, with whatever is behind it faintly present through the last
    /// eight percent. One value, shared with the Android client and with MiniMate.
    static let surface = Color.black.opacity(0.92)
    static let mustard = Color(red: 0xD8 / 255, green: 0xA6 / 255, blue: 0x28 / 255)
    static let live = Color(red: 0x2E / 255, green: 0x9E / 255, blue: 0x5B / 255)
    static let danger = Color(red: 0xC0 / 255, green: 0x39 / 255, blue: 0x2B / 255)
}
