#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <android/log.h>
#include <stdint.h>
#include <time.h>

#define TAG "uinput_mouse"

/* REL_WHEEL_HI_RES / REL_HWHEEL_HI_RES were added in kernel 4.15.
 * Define fallback values in case the NDK headers are older. */
#ifndef REL_WHEEL_HI_RES
#define REL_WHEEL_HI_RES  0x0b
#endif
#ifndef REL_HWHEEL_HI_RES
#define REL_HWHEEL_HI_RES 0x0c
#endif

static int mouse_fd = -1;

static void emit(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

JNIEXPORT jint JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_createMouseDevice(JNIEnv *env, jobject thiz) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open /dev/uinput failed: %s", strerror(errno));
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);

    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
    /* High-resolution scroll (120 units = 1 detent) */
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL_HI_RES);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL_HI_RES);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor  = 0x1234;
    usetup.id.product = 0x5678;
    strcpy(usetup.name, "BetterTouchpad Virtual Mouse");

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

    usleep(100000); // wait for device to be created
    mouse_fd = fd;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Mouse device created fd=%d", fd);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_sendRelMove(JNIEnv *env, jobject thiz, jint fd, jint dx, jint dy) {
    if (fd < 0) return;
    if (dx != 0) emit(fd, EV_REL, REL_X, dx);
    if (dy != 0) emit(fd, EV_REL, REL_Y, dy);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_sendWheel(JNIEnv *env, jobject thiz, jint fd, jint v, jint h) {
    if (fd < 0) return;
    if (v != 0) emit(fd, EV_REL, REL_WHEEL, v);
    if (h != 0) emit(fd, EV_REL, REL_HWHEEL, h);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

/*
 * High-resolution scroll. v and h are hi-res units (120 = one detent).
 * We emit REL_WHEEL_HI_RES / REL_HWHEEL_HI_RES for smooth pixel-level scrolling,
 * and also emit integer REL_WHEEL / REL_HWHEEL ticks using a running accumulator
 * so legacy apps that only understand integer ticks still work.
 */
static int hiResAccV = 0;
static int hiResAccH = 0;

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_sendWheelHiRes(JNIEnv *env, jobject thiz, jint fd, jint v, jint h) {
    if (fd < 0) return;

    if (v != 0) {
        emit(fd, EV_REL, REL_WHEEL_HI_RES, v);
        hiResAccV += v;
        int ticks = hiResAccV / 120;
        if (ticks != 0) {
            emit(fd, EV_REL, REL_WHEEL, ticks);
            hiResAccV -= ticks * 120;
        }
    }
    if (h != 0) {
        emit(fd, EV_REL, REL_HWHEEL_HI_RES, h);
        hiResAccH += h;
        int ticks = hiResAccH / 120;
        if (ticks != 0) {
            emit(fd, EV_REL, REL_HWHEEL, ticks);
            hiResAccH -= ticks * 120;
        }
    }
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_sendMouseButton(JNIEnv *env, jobject thiz, jint fd, jint btn, jboolean down) {
    if (fd < 0) return;
    emit(fd, EV_KEY, (uint16_t)btn, down ? 1 : 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_destroyMouseDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return;
    hiResAccV = 0;
    hiResAccH = 0;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Mouse device destroyed fd=%d", fd);
}

