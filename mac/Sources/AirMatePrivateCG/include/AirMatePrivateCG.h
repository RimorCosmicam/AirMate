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
/// Change a live display's mode in place, keeping its display ID.
///
/// Destroying and recreating the display strands whatever windows were on it and hands macOS a
/// different display ID, so the arrangement is lost as well. Re-applying settings keeps both.
bool AMVirtualDisplayApplyMode(
    AMVirtualDisplayHandle _Nonnull handle,
    uint32_t width,
    uint32_t height,
    double refreshRate,
    bool hiDPI,
    const char * _Nullable * _Nonnull errorMessage
);
void AMVirtualDisplayDestroy(AMVirtualDisplayHandle _Nullable handle);

#ifdef __cplusplus
}
#endif

