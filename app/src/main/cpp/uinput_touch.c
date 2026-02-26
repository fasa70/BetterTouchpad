#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <android/log.h>
#include <stdint.h>

#define TAG "uinput_touch"
#define MAX_SLOTS 3

static void emit(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

JNIEXPORT jint JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_createTouchDevice(JNIEnv *env, jobject thiz,
                                                               jint screen_width, jint screen_height) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open /dev/uinput failed: %s", strerror(errno));
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    struct uinput_abs_setup abs_x = {
        .code = ABS_MT_POSITION_X,
        .absinfo = { .value=0, .minimum=0, .maximum=screen_width-1, .resolution=0 }
    };
    struct uinput_abs_setup abs_y = {
        .code = ABS_MT_POSITION_Y,
        .absinfo = { .value=0, .minimum=0, .maximum=screen_height-1, .resolution=0 }
    };
    struct uinput_abs_setup abs_slot = {
        .code = ABS_MT_SLOT,
        .absinfo = { .value=0, .minimum=0, .maximum=MAX_SLOTS-1, .resolution=0 }
    };
    struct uinput_abs_setup abs_id = {
        .code = ABS_MT_TRACKING_ID,
        .absinfo = { .value=0, .minimum=0, .maximum=65535, .resolution=0 }
    };
    struct uinput_abs_setup abs_sx = {
        .code = ABS_X,
        .absinfo = { .value=0, .minimum=0, .maximum=screen_width-1, .resolution=0 }
    };
    struct uinput_abs_setup abs_sy = {
        .code = ABS_Y,
        .absinfo = { .value=0, .minimum=0, .maximum=screen_height-1, .resolution=0 }
    };

    ioctl(fd, UI_ABS_SETUP, &abs_x);
    ioctl(fd, UI_ABS_SETUP, &abs_y);
    ioctl(fd, UI_ABS_SETUP, &abs_slot);
    ioctl(fd, UI_ABS_SETUP, &abs_id);
    ioctl(fd, UI_ABS_SETUP, &abs_sx);
    ioctl(fd, UI_ABS_SETUP, &abs_sy);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor  = 0x1234;
    usetup.id.product = 0x5679;
    strcpy(usetup.name, "BetterTouchpad Virtual Touch");

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "UI_DEV_SETUP failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    usleep(100000);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Touch device created fd=%d, %dx%d", fd, screen_width, screen_height);
    return fd;
}

// points: flat array [slot0, x0, y0, trackingId0, slot1, x1, y1, trackingId1, ...]
// count: number of touch points
JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_injectTouch(JNIEnv *env, jobject thiz,
                                                          jint fd, jintArray points, jint count) {
    if (fd < 0 || count <= 0) return;
    jint *pts = (*env)->GetIntArrayElements(env, points, NULL);
    for (int i = 0; i < count; i++) {
        int slot = pts[i * 4 + 0];
        int x    = pts[i * 4 + 1];
        int y    = pts[i * 4 + 2];
        int tid  = pts[i * 4 + 3];
        emit(fd, EV_ABS, ABS_MT_SLOT, slot);
        emit(fd, EV_ABS, ABS_MT_TRACKING_ID, tid);
        if (tid >= 0) {
            emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
            emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
        }
    }
    emit(fd, EV_SYN, SYN_REPORT, 0);
    (*env)->ReleaseIntArrayElements(env, points, pts, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_releaseAllTouches(JNIEnv *env, jobject thiz, jint fd, jint count) {
    if (fd < 0) return;
    for (int i = 0; i < count; i++) {
        emit(fd, EV_ABS, ABS_MT_SLOT, i);
        emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    }
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_destroyTouchDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Touch device destroyed fd=%d", fd);
}

