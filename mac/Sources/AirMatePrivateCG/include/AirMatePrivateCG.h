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
void AMVirtualDisplayDestroy(AMVirtualDisplayHandle _Nullable handle);

#ifdef __cplusplus
}
#endif

