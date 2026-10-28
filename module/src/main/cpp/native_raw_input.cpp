#include "raw_dispatch_policy.h"

#include <android/log.h>
#include <atomic>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <unistd.h>

namespace {
using Hook = int (*)(void*, void*, void**);
using OnLoaded = void (*)(const char*, void*);
struct NativeAPIEntries {
    uint32_t version;
    Hook hook_func;
    int (*unhook_func)(void*);
};

Hook hook_function;
mistep::Publish original_publish;
std::atomic<bool> installed{false};
std::atomic<int> diagnostic_count{0};
std::atomic<int> rotation_diagnostic_count{0};
pthread_mutex_t config_lock = PTHREAD_MUTEX_INITIALIZER;
mistep::Zoom active_zoom;

int replacement_publish(void* dispatcher, void* connection, void* entry) {
    pthread_mutex_lock(&config_lock);
    const mistep::Zoom zoom = active_zoom;
    pthread_mutex_unlock(&config_lock);
    bool remapped = false;
    const auto local = entry == nullptr ? mistep::Transform{} : mistep::read<mistep::Transform>(entry, 0x1c);
    const int result = mistep::publish(original_publish, dispatcher, connection, entry,
            zoom, &remapped);
    if (remapped && diagnostic_count.fetch_add(1, std::memory_order_relaxed) < 6) {
        __android_log_print(ANDROID_LOG_INFO, "MiStepRawInput",
                "signed raw inverse display=0 scale=%f y=%f x=%f result=%d",
                zoom.scale, zoom.offset_y, zoom.offset_x, result);
    }
    if (remapped && std::fabs(local.m[0]) < .01f
            && rotation_diagnostic_count.fetch_add(1, std::memory_order_relaxed) < 8) {
        __android_log_print(ANDROID_LOG_INFO, "MiStepRawInput",
                "fixed raw axes basis=%f,%f,%f,%f display=%d bounds=%dx%d",
                local.m[0],local.m[1],local.m[3],local.m[4],
                zoom.display_rotation,zoom.width,zoom.height);
    }
    return result;
}

int find_and_hook(dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name == nullptr
            || std::strcmp(info->dlpi_name, "/system/lib64/libinputflinger.so") != 0) return 0;
    struct Profile { uintptr_t rva; const char* name; uint32_t prologue[16]; };
    constexpr Profile profiles[] = {{0x420434,"17PM-d5287b73",{
        0xd503233f, 0xfc1a0fe8, 0xa9017bfd, 0xa90267fc,
        0xa9035ff8, 0xa90457f6, 0xa9054ff4, 0x910043fd,
        0xd12603ff, 0xf90003ff, 0xaa0203f4, 0xaa0103f3,
        0xf9400455, 0xaa0003f6, 0x910283e0, 0x2a1f03e1
    }},{0x431a70,"xaga-34df4cf5",{
        0xd503245f,0xfc1a0fe8,0xa900fbfd,0xf9000ffc,
        0xa90267fa,0xa9035ff8,0xa90457f6,0xa9054ff4,
        0x910023fd,0xd126c3ff,0xf90003ff,0xd53bd058,
        0xf9401708,0xaa0203f4,0xaa0103f3,0xaa0003f6
    }}};
    for (const auto& profile : profiles) {
    const auto rva=profile.rva;
    bool executable = false;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const auto& segment = info->dlpi_phdr[i];
        if (segment.p_type == PT_LOAD && (segment.p_flags & PF_X)
                && rva >= segment.p_vaddr && rva + sizeof(profile.prologue) <= segment.p_vaddr + segment.p_memsz)
            executable = true;
    }
    if (!executable) continue;
    auto* address = reinterpret_cast<void*>(info->dlpi_addr + rva);
    if (std::memcmp(address, profile.prologue, sizeof(profile.prologue)) != 0) continue;
    const int result = hook_function(address, reinterpret_cast<void*>(replacement_publish),
            reinterpret_cast<void**>(&original_publish));
    installed.store(result == 0 && original_publish != nullptr, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "MiStepRawInput",
            "publish hook installed=%d result=%d profile=%s",
            installed.load(), result, profile.name);
    return 1;
    }
    __android_log_print(ANDROID_LOG_ERROR,"MiStepRawInput","publish profile mismatch; skipped");
    return 1;
}

void on_loaded(const char* name, void*) {
    if (!installed.load(std::memory_order_acquire) && name != nullptr
            && std::strstr(name, "libinputflinger.so") != nullptr)
        dl_iterate_phdr(find_and_hook, nullptr);
}
}  // namespace

extern "C" __attribute__((visibility("default"), used))
OnLoaded native_init(const NativeAPIEntries* api) {
    const uid_t uid = getuid();
    if (api == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "MiStepRawInput", "native_init api=null uid=%d", uid);
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "MiStepRawInput",
            "native_init entered uid=%d api_version=%u hook=%d", uid, api->version,
            api->hook_func != nullptr);
    if (uid != 1000 || api->hook_func == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "MiStepRawInput",
                "native_init rejected uid=%d hook=%d", uid, api->hook_func != nullptr);
        return nullptr;
    }
    hook_function = api->hook_func;
    const int found = dl_iterate_phdr(find_and_hook, nullptr);
    if (!installed.load(std::memory_order_acquire) && found == 0) {
        __android_log_print(ANDROID_LOG_INFO, "MiStepRawInput",
                "native_init waiting for libinputflinger load");
    }
    return on_loaded;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jianguo_mistep_NativeRawInputBridge_updateNative(JNIEnv*, jclass,
        jfloat scale, jfloat offset_y, jfloat offset_x, jint width, jint height, jint rotation) {
    pthread_mutex_lock(&config_lock);
    active_zoom = {scale, offset_y, offset_x, width, height, rotation};
    pthread_mutex_unlock(&config_lock);
    return installed.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
