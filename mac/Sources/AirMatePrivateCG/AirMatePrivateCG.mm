#import "AirMatePrivateCG.h"
#import <AppKit/AppKit.h>

// Undocumented declarations are intentionally confined to this translation unit.
@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(NSUInteger)width height:(NSUInteger)height refreshRate:(CGFloat)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property(nonatomic, assign) unsigned int rotation;
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
// Read back so a rotation can put them straight back untouched, rather than re-deriving a mode
// and turning a rotation into a reconfiguration.
@property(nonatomic, readonly) unsigned int hiDPI;
@property(nonatomic, readonly) unsigned int rotation;
@property(nonatomic, readonly) NSArray<CGVirtualDisplayMode *> *modes;
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
    }
}

bool AMVirtualDisplaySetRotation(AMVirtualDisplayHandle handle, uint32_t degrees,
                                 const char **errorMessage) {
    @autoreleasepool {
        if (!handle) {
            AMSetError(@"No virtual display to rotate", errorMessage);
            return false;
        }
        Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
        if (!settingsClass) {
            AMSetError(@"CGVirtualDisplaySettings is unavailable on this macOS version", errorMessage);
            return false;
        }
        CGVirtualDisplay *display = (__bridge CGVirtualDisplay *)handle;

        // Rotation only. The modes and the HiDPI flag are read back off the live display and put
        // straight back, because changing width by height is a display reconfiguration — macOS
        // refuses it on a running display, and when it does not, WindowServer tears the display
        // down and takes the windows on it with it. Rotation is a transform of the same display.
        CGVirtualDisplaySettings *settings = [[settingsClass alloc] init];
        settings.hiDPI = display.hiDPI;
        settings.modes = display.modes;
        settings.rotation = degrees;
        if (![display applySettings:settings]) {
            AMSetError(@"CGVirtualDisplay rejected the rotation", errorMessage);
            return false;
        }
        *errorMessage = nullptr;
        return true;
    }
}

void AMVirtualDisplayDestroy(AMVirtualDisplayHandle handle) {
    if (handle) CFRelease(handle);
}

