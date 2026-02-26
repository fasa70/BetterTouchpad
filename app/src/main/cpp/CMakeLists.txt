cmake_minimum_required(VERSION 3.22.1)
project("bettertouchpad")

add_library(
        touchpad_jni
        SHARED
        touchpad_bridge.c
        uinput_mouse.c
        uinput_touch.c
        evdev_grab.c
)

find_library(log-lib log)

target_link_libraries(
        touchpad_jni
        ${log-lib}
)

# Root helper executable — named libroot_helper.so so Android packages it in nativeLibraryDir.
# It is actually a standalone executable, not a shared lib, but the .so extension is required
# for the APK packager to include it. We exec it via su at runtime.
add_executable(root_helper root_helper.c)
target_compile_options(root_helper PRIVATE -fPIE)
target_link_options(root_helper PRIVATE -fPIE -pie)
set_target_properties(root_helper PROPERTIES
        OUTPUT_NAME "libroot_helper"
        SUFFIX ".so"
)
