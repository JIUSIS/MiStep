#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>

namespace mistep {

// Layout verified against the gated 17PM libui and libinputflinger binaries.
struct Transform {
    float m[9];  // Column-major affine matrix.
    uint32_t type;
};
static_assert(sizeof(Transform) == 40);

struct Zoom {
    float scale = 1;
    float offset_y = 0;
    float offset_x = 0;
};

template <typename T> inline T read(const void* address, size_t offset) {
    T value;
    std::memcpy(&value, static_cast<const uint8_t*>(address) + offset, sizeof(T));
    return value;
}

inline bool close(float a, float b) {
    return std::isfinite(a) && std::fabs(a - b) <= 0.002f * std::fmax(1.f, std::fabs(b));
}

inline bool affine(const Transform& t) {
    for (float v : t.m) if (!std::isfinite(v)) return false;
    return close(t.m[2], 0) && close(t.m[5], 0) && close(t.m[8], 1);
}

inline bool uniform_basis(const Transform& t, float scale) {
    return affine(t) && close(std::hypot(t.m[0], t.m[1]), scale)
            && close(std::hypot(t.m[3], t.m[4]), scale)
            && close(t.m[0] * t.m[3] + t.m[1] * t.m[4], 0);
}

inline bool corrected_raw(const Zoom& zoom, const Transform& local,
        const Transform& raw, Transform& out) {
    if (!std::isfinite(zoom.scale) || zoom.scale <= 0 || zoom.scale >= 1
            || !std::isfinite(zoom.offset_x) || !std::isfinite(zoom.offset_y)) return false;
    const float inverse = 1 / zoom.scale;
    if (!std::isfinite(inverse) || !uniform_basis(local, inverse)
            || !uniform_basis(raw, 1)) return false;
    out = raw;
    out.m[0] *= inverse;
    out.m[1] *= inverse;
    out.m[3] *= inverse;
    out.m[4] *= inverse;
    out.m[6] = (raw.m[6] - zoom.offset_x) * inverse;
    out.m[7] = (raw.m[7] - zoom.offset_y) * inverse;
    out.type = 0x80000000u;  // Transform::UNKNOWN_TYPE: let libui derive cached flags.
    return affine(out);
}

using Publish = int (*)(void*, void*, void*);

// The original function signs and publishes this same temporary raw matrix.
// Restore it even on a failed/retried publish; never touch the local matrix/samples.
inline int publish(Publish original, void* dispatcher, void* connection, void* entry,
        const Zoom& zoom, bool* remapped = nullptr) {
    if (remapped) *remapped = false;
    if (zoom.scale > 0 && zoom.scale < 1 && entry != nullptr) {
        const void* event = read<const void*>(entry, 0x8);
        if (event != nullptr && read<int32_t>(event, 0xc) == 3
                && (read<uint32_t>(event, 0x38) & 2) != 0
                && read<int32_t>(event, 0x3c) == 0
                && (read<uint32_t>(entry, 0x18) & 8) == 0
                && read<float>(entry, 0x6c) == 1.f) {
            const Transform local = read<Transform>(entry, 0x1c);
            const Transform raw = read<Transform>(entry, 0x44);
            Transform corrected;
            if (corrected_raw(zoom, local, raw, corrected)) {
                auto* target = static_cast<uint8_t*>(entry) + 0x44;
                std::memcpy(target, &corrected, sizeof(corrected));
                const int result = original(dispatcher, connection, entry);
                std::memcpy(target, &raw, sizeof(raw));
                if (remapped) *remapped = true;
                return result;
            }
        }
    }
    return original(dispatcher, connection, entry);
}

}  // namespace mistep
