#pragma once
#include <CoreGraphics/CoreGraphics.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *AMVirtualDisplayHandle;

AMVirtualDisplayHandle _Nullable AMVirtualDisplayCreate(
    const char * _Nonnull name,
    uint32_t width,
    uint32_t height,
    double refreshRate,
    bool hiDPI,
    CGDirectDisplayID * _Nonnull displayID,
    const char * _Nullable * _Nonnull errorMessage
);
/// Rotate a live display, keeping its ID, its mode and the windows on it.
///
/// `CGVirtualDisplaySettings` carries a rotation independently of the display's modes. Changing the
/// mode of a running display is a reconfiguration that macOS refuses; rotating it is not.
bool AMVirtualDisplaySetRotation(
    AMVirtualDisplayHandle _Nonnull handle,
    uint32_t degrees,
    const char * _Nullable * _Nonnull errorMessage
);
void AMVirtualDisplayDestroy(AMVirtualDisplayHandle _Nullable handle);

#ifdef __cplusplus
}
#endif

