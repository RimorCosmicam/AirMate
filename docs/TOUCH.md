# Native touch: what is known

AirMate currently drives the Mac by synthesising `CGEvent` clicks and scrolls and restoring the
cursor afterwards. That is mouse emulation, and it is a compatibility fallback rather than the
intended design. The intended design is that the tablet is a touch display: raw contacts travel to
the Mac and macOS's own touch and gesture machinery interprets them.

This file records what has been established about that, so the next attempt starts from evidence
rather than from guessing. Everything below was measured on this machine, on macOS 27.0
(build 26A5421a), by runtime introspection.

## What exists

| Thing | State |
|---|---|
| `NSScreen.touchCapabilities` | Exists. Returns `Q`, a bitmask. |
| `NSScreen._isTouchScreen` | Exists, private, returns `BOOL`. |
| `NSTouch`, `NSTouchTypeDirect` | Exist. Direct touch is a real modality, distinct from indirect. |
| `NSGestureRecognizer`, `NSClickGestureRecognizer`, `NSPanGestureRecognizer` | Exist. |
| `NSScrollGestureRecognizer` | **Does not exist.** Not in the runtime under that name. |

Both screens on this machine — the built-in one and AirMate's virtual display — report
`touchCapabilities = 0` and `_isTouchScreen = false`. So the capability is real, it is queryable,
and our display is not in it.

The installed SDK is 26.1 while the OS is 27.0, so none of the above appears in the local headers.
Compiling against `touchCapabilities` needs the macOS 27 SDK, which means Xcode 27 rather than the
Command Line Tools.

## What blocks it

`IOHIDUserDeviceCreate` and its relatives are **not exported** from IOKit. Creating a virtual HID
digitizer from user space is not a public path on this OS. The supported route is a DriverKit
extension using `HIDDriverKit`, which requires:

- a driver extension, signed and notarised,
- the `com.apple.developer.driverkit.family.hid.device` entitlement, which Apple grants by request,
- a paid developer account.

An ad-hoc signed application cannot publish a HID device at all. This is the gate on the whole
approach, and it is administrative rather than technical.

## Dead ends already walked

- **`SkyLight.framework`** exports no touch or digitizer symbols at all, so `_isTouchScreen` is not
  reading an exported window-server function.
- **`CoreGraphics`** likewise exports nothing touch-related.
- **`HIDDisplay.framework`** looked like the association mechanism from its name. It is not: its six
  classes — `HIDDisplayPresetInterface`, `HIDDisplayUserAdjustmentInterface`,
  `HIDDisplayDeviceManagementInterface`, `HIDDisplayIOReportingInterface`, `HIDDisplayPresetData`,
  `HIDDisplayInterface` — are display calibration and preset control, the HID transport used to
  drive monitor settings. Nothing to do with touch input.

## The open question

Nothing found so far says how a digitizer is bound to a display. The most promising lead is
`HIDDisplayGetContainerID`, an exported symbol in that same framework: container ID is how macOS
groups a display with the HID devices belonging to the same physical unit. A real touch monitor's
digitizer and panel share one. A virtual display has no physical container, so whether one can be
declared is the question to answer next.

The method for answering it is a diff, and it needs hardware this machine does not have: attach a
genuinely touch-capable display, dump the IORegistry around both the display service and the HID
digitizer service, and compare that structure against AirMate's virtual display. The missing
relationship is what to implement.

## Rotating the virtual display: measured, and abandoned

Turning the Mac's display with the tablet was tried three ways. All three are recorded here so the
fourth attempt starts from evidence.

1. **Re-mode a running display** — `applySettings:` carrying new modes. Refused every time on a
   live display, logged as `In-place resize refused`.
2. **Destroy and recreate at the new size** — accepted, and destructive. macOS is handed a
   different display, so every window on the old one is stranded and the replacement starts empty.
   The client, receiving nothing on the new session, kept showing the last frame of the old one,
   scaled into the new geometry: a frozen, stretched still.
3. **`CGVirtualDisplaySettings.rotation`** — the property exists, has a `setRotation:`, and
   `applySettings:` **accepts it**, logged as `rotated to 90°`. But it does not rotate the
   framebuffer: the display stays landscape while the capture is reconfigured to portrait, giving a
   small landscape image inside a portrait panel. Worse, handing `display.modes` back through
   `applySettings:` corrupts the display's effective size — after a round trip the capture showed
   only the top-left quadrant.

So the client rotates alone and the picture letterboxes. It is a smaller image, it is instant, it
keeps every window where it was, and it cannot fail. Anyone revisiting this needs to establish what
actually drives the framebuffer's orientation, because the rotation property is not it.

## Order of work

The entitlement has a long lead time and only the account holder can request it, so it should be
started first and in parallel. But the association is the part that can fail outright — a driver
extension that publishes a perfect digitizer is worth nothing if nothing can bind it to the
display. Establish the binding before building the extension.

Until then the synthesised path stays, explicitly as a fallback.
