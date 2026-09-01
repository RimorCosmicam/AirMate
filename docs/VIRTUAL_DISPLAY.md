# Virtual display: creation, and what reconfiguring it actually does

Written for someone advising on portrait/landscape switching. Everything here was measured on
macOS 27.0 (build 26A5421a), on an ad-hoc signed app, by runtime introspection and by reading the
log of real attempts.

**Short version:** "don't destroy the display, just change its geometry" is the first thing we
tried. `applySettings:` carrying new modes on a live display returns `NO`. Every time.

## How the display is created

`CGVirtualDisplay`, via the private CoreGraphics classes, isolated in one translation unit.

```objc
        CGVirtualDisplayDescriptor *descriptor = [[descriptorClass alloc] init];
        descriptor.name = [NSString stringWithUTF8String:name];
        descriptor.maxPixelsWide = width;
        descriptor.maxPixelsHigh = height;
        descriptor.sizeInMillimeters = CGSizeMake(286, 179);
        descriptor.vendorID = 0x414d;
        descriptor.productID = 1;
        descriptor.serialNum = 1;
        descriptor.queue = dispatch_get_main_queue();
        descriptor.terminationHandler = ^(id reason, CGVirtualDisplay *display) {
            NSLog(@"AirMate.Display virtual display terminated: %@", reason);
        };

        CGVirtualDisplay *display = [[displayClass alloc] initWithDescriptor:descriptor];
        if (!display) {
            AMSetError(@"CGVirtualDisplay initialization failed", errorMessage);
            return nullptr;
        }

        CGVirtualDisplayMode *mode = [[modeClass alloc] initWithWidth:width height:height refreshRate:refreshRate];
        CGVirtualDisplaySettings *settings = [[settingsClass alloc] init];
        settings.hiDPI = hiDPI ? 1 : 0;
        settings.modes = @[mode];
        if (![display applySettings:settings]) {
            AMSetError(@"CGVirtualDisplay rejected the requested settings", errorMessage);
            return nullptr;
        }
        *displayID = display.displayID;
        *errorMessage = nullptr;
        return (__bridge_retained void *)display;
```

## The private classes, as they actually are

Dumped from the live runtime with `class_copyPropertyList`, not from a header.

```
CGVirtualDisplaySettings
  prop refreshDeadline  [Td,N]
  prop isReference      [TB,N]
  prop modes            [T@"NSArray",&,N]
  prop hiDPI            [TI,N]
  prop rotation         [TI,N]        <-- has a setRotation:

CGVirtualDisplay   (all readonly)
  prop displayID, hiDPI, rotation, modes, name, maxPixelsWide, maxPixelsHigh, ...
  - initWithDescriptor:
  - applySettings:
```

So there is exactly one mutating entry point on a live display: `applySettings:`.

## What was tried, and what happened

### 1. Change the modes on the live display

Build a `CGVirtualDisplaySettings`, set `modes` to a single new `CGVirtualDisplayMode` at the
swapped size, keep `hiDPI`, call `applySettings:`.

**Result: returns `NO`.** Logged as `In-place resize refused, rebuilding the display` on every
attempt. This is the approach in the advice above; macOS does not accept it.

### 2. Destroy the display and create a new one at the swapped size

**Result: works, and is destructive.** macOS is handed a different display with a different ID, so
every window on the old one is relocated and the new one starts empty. Because ScreenCaptureKit
only emits on change, an empty new display produces no frames at all, so the client sat on the last
frame of the previous session — scaled into the new geometry, which is the "stretched freeze frame".

### 3. `settings.rotation = 90`, modes handed back untouched

Read `display.modes` and `display.hiDPI` off the live display, put them straight back, change only
`rotation`, call `applySettings:`.

**Result: returns `YES`, and does nothing.** Logged as `AirMate Display rotated to 90°`. The
framebuffer stayed landscape, so the capture was reconfigured to portrait around a landscape
display and produced a small landscape image inside a portrait panel. Worse, round-tripping
`display.modes` through `applySettings:` corrupted the display's effective size — returning to
landscape then showed only the top-left quadrant.

Both 90 and 1 were tried, in case the property counts quarter turns rather than degrees.

Measured directly, on a throwaway virtual display so nothing else was disturbed:

```
create applySettings -> YES
displayID 56, rotation 0
after create           bounds 960 x 540
rotate(90) applySettings -> YES
display.rotation now 0
after rotate(90)       bounds 960 x 540
```

`applySettings:` returns `YES` and the rotation does not even persist on the object — it reads back
as `0` — and the display's bounds never change. So this is not a case of the capture pipeline
failing to follow the display. There is nothing to follow. The rotation property on this class is
accepted and inert.

The shim that called it has been deleted rather than left in place, because code that looks like a
working mechanism and is not one is worse than no code at all.

## Where that leaves it

Only (2) produces a genuinely portrait desktop, so that is what ships, with the cost accepted: the
display is rebuilt, windows on it are relocated, and a curtain covers the gap and does not lift
until decoded video from the new display arrives.

The open question for anyone who wants (1) or (3) to work: **what actually drives this display's
framebuffer orientation?** `settings.rotation` is accepted and inert, and `modes` cannot be changed
on a live display. If there is a third mechanism, it is not on these two classes.

## The capture side, for completeness

Capture is ScreenCaptureKit against the display ID, configured at the display's size:

```swift
let configuration = SCStreamConfiguration()
configuration.width = width
configuration.height = height
configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
configuration.queueDepth = 3
configuration.pixelFormat = kCVPixelFormatType_32BGRA
let filter = SCContentFilter(display: display, excludingWindows: [])
```

On any geometry change the encoder and the stream are both rebuilt at the new size, and a keyframe
is forced, because the client discards its reference frames when the session ID changes.
