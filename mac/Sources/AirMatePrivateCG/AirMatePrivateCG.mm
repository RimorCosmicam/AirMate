#import "AirMatePrivateCG.h"
#import <AppKit/AppKit.h>

// Undocumented declarations are intentionally confined to this translation unit.
@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(NSUInteger)width height:(NSUInteger)height refreshRate:(CGFloat)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property(nonatomic, retain) NSArray<CGVirtualDisplayMode *> *modes;
@property(nonatomic) unsigned int hiDPI;
@end

@class CGVirtualDisplay;
@interface CGVirtualDisplayDescriptor : NSObject
@property(nonatomic, retain) dispatch_queue_t queue;
@property(nonatomic, copy) NSString *name;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int serialNum;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int vendorID;
@property(nonatomic, copy) void (^terminationHandler)(id, CGVirtualDisplay *);
@end

@interface CGVirtualDisplay : NSObject
@property(nonatomic, readonly) CGDirectDisplayID displayID;
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

static const char *AMSetError(NSString *message, const char **errorMessage) {
    static __thread char buffer[512];
    snprintf(buffer, sizeof(buffer), "%s", message.UTF8String ?: "Unknown error");
    *errorMessage = buffer;
    return buffer;
}

AMVirtualDisplayHandle AMVirtualDisplayCreate(const char *name, uint32_t width, uint32_t height,
                                               double refreshRate, bool hiDPI,
                                               CGDirectDisplayID *displayID, const char **errorMessage) {
    @autoreleasepool {
        Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
        Class displayClass = NSClassFromString(@"CGVirtualDisplay");
        Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
        Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
        if (!descriptorClass || !displayClass || !modeClass || !settingsClass) {
            AMSetError(@"CGVirtualDisplay classes are unavailable on this macOS version", errorMessage);
            return nullptr;
        }

        CGVirtualDisplayDescriptor *descriptor = [[descriptorClass alloc] init];
        descriptor.name = [NSString stringWithUTF8String:name];
        descriptor.maxPixelsWide = width;
        descriptor.maxPixelsHigh = height;
        // Derived from the resolution, not fixed. macOS reads pixels over millimetres and halves
        // the logical size once that crosses roughly 150 dpi, so a panel declared 286mm wide was
        // fine at 1600 pixels and became a 904 x 544 display at 1808. Held near 110 dpi — an
        // ordinary desktop monitor — the display is whatever was asked for.
        const double kTargetDPI = 110.0;
        descriptor.sizeInMillimeters = CGSizeMake(width / kTargetDPI * 25.4, height / kTargetDPI * 25.4);
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
    }
}

void AMVirtualDisplayDestroy(AMVirtualDisplayHandle handle) {
    if (handle) CFRelease(handle);
}

