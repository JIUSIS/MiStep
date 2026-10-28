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

    // Same fixed-rotation window input contract for every app/IME. Moving-popup
    // origins never enter raw; raw directions and distances follow local axes.
    for (int rotation : {0,1,2,3}) for(float ox : {0.f,273.f})
    for(float origin : {-1900.f,0.f,1400.f}) {
        const mistep::Zoom state{.7471545f,622.f,ox,1080,2460,0};
        auto window=identity(); const float k=1.f/state.scale;
        if(rotation==0){ window.m[0]=k;window.m[4]=k; }
        if(rotation==1){ window.m[0]=0;window.m[1]=-k;window.m[3]=k;window.m[4]=0; }
        if(rotation==2){ window.m[0]=-k;window.m[4]=-k; }
        if(rotation==3){ window.m[0]=0;window.m[1]=k;window.m[3]=-k;window.m[4]=0; }
        window.m[6]=origin;window.m[7]=-origin;
        mistep::Transform mapped;
        check(mistep::corrected_raw(state,window,raw,mapped),"fixed rotation raw composed");
        auto aliased_raw=raw;
        check(mistep::corrected_raw(state,window,aliased_raw,aliased_raw)
                && std::memcmp(&aliased_raw,&mapped,sizeof(mapped))==0,
                "aliased raw input preserves fixed rotation");
        auto aliased_local=window;
        check(mistep::corrected_raw(state,aliased_local,raw,aliased_local)
                && std::memcmp(&aliased_local,&mapped,sizeof(mapped))==0,
                "aliased local input preserves fixed rotation");
        for(float x : {20.f,500.f,1060.f}) for(float y : {50.f,1300.f,2400.f}) {
            float px=x*state.scale+ox,py=y*state.scale+state.offset_y;
            float expected_x=rotation==0 ? x : rotation==1 ? y : rotation==2 ? 1080-x : 2460-y;
            float expected_y=rotation==0 ? y : rotation==1 ? 1080-x : rotation==2 ? 2460-y : x;
            check(std::fabs(mapped.m[0]*px+mapped.m[3]*py+mapped.m[6]-expected_x)<.003f,"absolute raw X follows fixed window");
            check(std::fabs(mapped.m[1]*px+mapped.m[4]*py+mapped.m[7]-expected_y)<.003f,"absolute raw Y follows fixed window");
        }
        write(entry,0x1c,window); write(entry,0x44,raw);
        for(int result : {0,-11,-32}){publish_result=result;run(state,entry,true);}
    }
    auto mirror=local;mirror.m[0]=-mirror.m[0];
    check(!mistep::corrected_raw({zoom.scale,622,0,1080,2460,0},mirror,raw,corrected),"mirrored unsupported input skipped");

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
