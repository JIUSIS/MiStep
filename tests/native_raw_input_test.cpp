#include "../module/src/main/cpp/raw_dispatch_policy.h"

#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <initializer_list>

namespace {
int checks;
int publish_result;
int original_calls;
mistep::Transform expected_raw;
mistep::Transform expected_local;

void check(bool success, const char* name) {
    if (!success) {
        std::fprintf(stderr, "FAIL: %s\n", name);
        std::exit(1);
    }
    ++checks;
}

mistep::Transform identity() {
    return {{1, 0, 0, 0, 1, 0, 0, 0, 1}, 0};
}

template <typename T> void write(void* data, size_t offset, const T& value) {
    std::memcpy(static_cast<uint8_t*>(data) + offset, &value, sizeof(value));
}

int original(void* dispatcher, void* connection, void* entry) {
    ++original_calls;
    check(dispatcher == reinterpret_cast<void*>(0x1234), "dispatcher preserved");
    check(connection == reinterpret_cast<void*>(0x5678), "connection preserved");
    const auto local = mistep::read<mistep::Transform>(entry, 0x1c);
    const auto raw = mistep::read<mistep::Transform>(entry, 0x44);
    check(std::memcmp(&local, &expected_local, sizeof(local)) == 0,
            "local matrix byte-identical during original publish");
    check(std::memcmp(&raw, &expected_raw, sizeof(raw)) == 0,
            "original signer and publisher see expected raw matrix");
    return publish_result;
}

void run(const mistep::Zoom& zoom, uint8_t* entry, bool remap) {
    uint8_t before[160];
    std::memcpy(before, entry, sizeof(before));
    expected_local = mistep::read<mistep::Transform>(entry, 0x1c);
    expected_raw = mistep::read<mistep::Transform>(entry, 0x44);
    if (remap) check(mistep::corrected_raw(zoom, expected_local, expected_raw, expected_raw),
            "expected inverse available");
    const int before_calls = original_calls;
    bool observed = !remap;
    const int result = mistep::publish(original, reinterpret_cast<void*>(0x1234),
            reinterpret_cast<void*>(0x5678), entry, zoom, &observed);
    check(observed == remap, "remap decision matches actual geometry");
    check(result == publish_result, "original return value preserved");
    check(original_calls == before_calls + 1, "original called exactly once");
    check(std::memcmp(before, entry, sizeof(before)) == 0,
            "entire entry restored after success or retry");
}
}  // namespace

int main() {
    alignas(8) uint8_t event[160]{};
    alignas(8) uint8_t entry[160]{};
    write<int32_t>(event, 0xc, 3);
    write<uint32_t>(event, 0x38, 0x1002);
    write<int32_t>(event, 0x3c, 0);
    write<const void*>(entry, 0x8, event);
    write<float>(entry, 0x6c, 1);
    const mistep::Zoom zoom{0.74654907f, 661, 0};
    auto local = identity();
    local.m[0] = local.m[4] = 1 / zoom.scale;
    local.m[6] = -140;  // A popup has its own local origin; raw must not inherit it.
    local.m[7] = -1900;
    const auto raw = identity();
    write(entry, 0x1c, local);
    write(entry, 0x44, raw);
    for (int result : {0, -11, -32}) {
        publish_result = result;
        run(zoom, entry, true);
    }
    for (float x_offset : {0.f, 304.f}) {
        const mistep::Zoom state{zoom.scale, zoom.offset_y, x_offset};
        mistep::Transform mapped;
        check(mistep::corrected_raw(state, local, raw, mapped), "main raw inverse exists");
        for (float y : {0.f, 100.f, 400.f, 1250.f, 2200.f, 2608.f}) {
            const float px = 600 * state.scale + state.offset_x;
            const float py = y * state.scale + state.offset_y;
            check(std::fabs(mapped.m[0] * px + mapped.m[3] * py + mapped.m[6] - 600) < .002f,
                    "left/right main raw X returns source coordinate");
            check(std::fabs(mapped.m[1] * px + mapped.m[4] * py + mapped.m[7] - y) < .002f,
                    "top/middle/bottom raw Y returns source coordinate");
        }
    }
    run({1, 0, 0}, entry, false);
    run({NAN, 0, 0}, entry, false);
    run({zoom.scale, INFINITY, 0}, entry, false);
    write(entry, 0x1c, raw);
    run(zoom, entry, false);  // Stale zoom lease, actual geometry already identity.
    auto side = identity();
    side.m[0] = side.m[4] = 4;
    write(entry, 0x1c, side);
    run(zoom, entry, false);
    write(entry, 0x1c, local);
    write<int32_t>(event, 0x3c, 1);
    run(zoom, entry, false);
    write<int32_t>(event, 0x3c, 0);
    write<uint32_t>(event, 0x38, 0x101);
    run(zoom, entry, false);
    write<uint32_t>(event, 0x38, 0x1002);
    write<int32_t>(event, 0xc, 2);
    run(zoom, entry, false);
    write<int32_t>(event, 0xc, 3);
    write<uint32_t>(entry, 0x18, 8);
    run(zoom, entry, false);
    write<uint32_t>(entry, 0x18, 0);
    write<float>(entry, 0x6c, .8f);
    run(zoom, entry, false);
    write<float>(entry, 0x6c, 1);
    mistep::Transform corrected;
    check(mistep::corrected_raw(zoom, local, raw, corrected), "first inverse available");
    write(entry, 0x44, corrected);
    run(zoom, entry, false);
    write(entry, 0x44, raw);
    auto rotated = raw;
    rotated.m[0] = rotated.m[4] = 0;
    rotated.m[1] = 1;
    rotated.m[3] = -1;
    rotated.m[6] = 2608;
    check(mistep::corrected_raw(zoom, local, rotated, corrected), "display rotation composes");
    check(std::fabs(corrected.m[3] * 100 + corrected.m[6] - 2508 / zoom.scale) < .002f,
            "raw display transform is applied before inverse zoom");

    void* libui = dlopen("/system/lib64/libui.so", RTLD_NOW);
    check(libui != nullptr, "real target libui opened in isolated process");
    using Set = void (*)(void*, float, float, float, float);
    using Get = float (*)(const void*);
    auto set = reinterpret_cast<Set>(dlsym(libui, "_ZN7android2ui9Transform3setEffff"));
    auto sx = reinterpret_cast<Get>(dlsym(libui, "_ZNK7android2ui9Transform4dsdxEv"));
    auto sy = reinterpret_cast<Get>(dlsym(libui, "_ZNK7android2ui9Transform4dsdyEv"));
    check(set != nullptr && sx != nullptr && sy != nullptr, "real Transform methods resolved");
    auto native_matrix = identity();
    set(&native_matrix, 2, 3, 4, 5);
    check(native_matrix.m[0] == 2 && native_matrix.m[3] == 3
            && native_matrix.m[1] == 4 && native_matrix.m[4] == 5
            && native_matrix.type == 0x80000000u, "40-byte column-major layout matches real libui");
    check(sx(&native_matrix) == 2 && sy(&native_matrix) == 5, "real libui reads expected basis");
    dlclose(libui);
    std::printf("NativeRawInput: %d checks passed; isolated process, no system hook installed\n", checks);
    return 0;
}
