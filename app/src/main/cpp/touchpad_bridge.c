#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <linux/input.h>
#include <android/log.h>
#include <poll.h>

#define TAG "touchpad_bridge"
#define MAX_SLOTS 10

// Globals
static volatile int g_running = 0;
static JavaVM *g_jvm = NULL;
static jobject g_callback_obj = NULL;
static jmethodID g_on_frame_method = NULL;
static jmethodID g_on_key_event_method = NULL;

// Per-slot tracking state
typedef struct {
    int tracking_id; // -1 = no finger
    int x;
    int y;
    int active;      // 1 = finger present
} SlotState;

static SlotState slots[MAX_SLOTS];
static int current_slot = 0;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_setCallback(JNIEnv *env, jobject thiz, jobject callback) {
    if (g_callback_obj != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_obj);
        g_callback_obj = NULL;
    }
    if (callback == NULL) return;
    g_callback_obj = (*env)->NewGlobalRef(env, callback);
    jclass cls = (*env)->GetObjectClass(env, g_callback_obj);
    g_on_frame_method = (*env)->GetMethodID(env, cls, "onFrame", "([I[I[I[II)V");
    g_on_key_event_method = (*env)->GetMethodID(env, cls, "onKeyEvent", "(II)V");
    if (!g_on_frame_method || !g_on_key_event_method) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get callback methods");
    }
}

static void dispatch_frame(JNIEnv *env,
                            int *frame_key_codes, int *frame_key_vals, int *frame_key_count) {
    if (!g_callback_obj) return;

    // Fire key events first
    for (int k = 0; k < *frame_key_count; k++) {
        (*env)->CallVoidMethod(env, g_callback_obj, g_on_key_event_method,
                               frame_key_codes[k], frame_key_vals[k]);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    *frame_key_count = 0;

    // Build arrays for onFrame
    jint slot_active[MAX_SLOTS], tracking_ids[MAX_SLOTS], xs[MAX_SLOTS], ys[MAX_SLOTS];
    for (int s = 0; s < MAX_SLOTS; s++) {
        slot_active[s]  = slots[s].active;
        tracking_ids[s] = slots[s].tracking_id;
        xs[s]           = slots[s].x;
        ys[s]           = slots[s].y;
    }

    jintArray jSA  = (*env)->NewIntArray(env, MAX_SLOTS);
    jintArray jTID = (*env)->NewIntArray(env, MAX_SLOTS);
    jintArray jX   = (*env)->NewIntArray(env, MAX_SLOTS);
    jintArray jY   = (*env)->NewIntArray(env, MAX_SLOTS);
    (*env)->SetIntArrayRegion(env, jSA,  0, MAX_SLOTS, slot_active);
    (*env)->SetIntArrayRegion(env, jTID, 0, MAX_SLOTS, tracking_ids);
    (*env)->SetIntArrayRegion(env, jX,   0, MAX_SLOTS, xs);
    (*env)->SetIntArrayRegion(env, jY,   0, MAX_SLOTS, ys);

    (*env)->CallVoidMethod(env, g_callback_obj, g_on_frame_method,
                           jSA, jTID, jX, jY, MAX_SLOTS);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, jSA);
    (*env)->DeleteLocalRef(env, jTID);
    (*env)->DeleteLocalRef(env, jX);
    (*env)->DeleteLocalRef(env, jY);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_startEventLoop(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "startEventLoop: invalid fd");
        return;
    }

    memset(slots, 0, sizeof(slots));
    for (int i = 0; i < MAX_SLOTS; i++) {
        slots[i].tracking_id = -1;
        slots[i].active = 0;
    }
    current_slot = 0;
    g_running = 1;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;

    int frame_key_codes[16];
    int frame_key_vals[16];
    int frame_key_count = 0;
    int consecutive_errors = 0;

    __android_log_print(ANDROID_LOG_INFO, TAG, "Event loop started fd=%d", fd);

    while (g_running) {
        int ret = poll(&pfd, 1, 200);
        if (ret < 0) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_ERROR, TAG, "poll error: %s", strerror(errno));
            break;
        }
        if (ret == 0) {
            // timeout — loop again to check g_running
            consecutive_errors = 0;
            continue;
        }
        if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "poll revents error: 0x%x", pfd.revents);
            break;
        }
        if (!(pfd.revents & POLLIN)) continue;

        struct input_event evbuf[64];
        ssize_t nread = read(fd, evbuf, sizeof(evbuf));
        if (nread < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "read error: %s (errno=%d)", strerror(errno), errno);
            // Don't break on transient errors — retry up to a limit
            if (++consecutive_errors > 20) break;
            usleep(10000);
            continue;
        }
        if (nread == 0) {
            // EOF — device disconnected
            __android_log_print(ANDROID_LOG_ERROR, TAG, "read EOF — device gone");
            break;
        }
        consecutive_errors = 0;

        int n = (int)(nread / sizeof(struct input_event));

        for (int i = 0; i < n; i++) {
            struct input_event *e = &evbuf[i];

            if (e->type == EV_ABS) {
                switch (e->code) {
                    case ABS_MT_SLOT:
                        current_slot = e->value;
                        if (current_slot < 0) current_slot = 0;
                        if (current_slot >= MAX_SLOTS) current_slot = MAX_SLOTS - 1;
                        break;
                    case ABS_MT_TRACKING_ID:
                        slots[current_slot].tracking_id = e->value;
                        slots[current_slot].active = (e->value != -1) ? 1 : 0;
                        break;
                    case ABS_MT_POSITION_X:
                        slots[current_slot].x = e->value;
                        break;
                    case ABS_MT_POSITION_Y:
                        slots[current_slot].y = e->value;
                        break;
                    default:
                        break;
                }
            } else if (e->type == EV_KEY) {
                if (frame_key_count < 16) {
                    frame_key_codes[frame_key_count] = e->code;
                    frame_key_vals[frame_key_count]  = e->value;
                    frame_key_count++;
                }
            } else if (e->type == EV_SYN) {
                if (e->code == SYN_REPORT) {
                    // Dispatch after we've processed all events up to this SYN_REPORT
                    dispatch_frame(env, frame_key_codes, frame_key_vals, &frame_key_count);
                } else if (e->code == SYN_DROPPED) {
                    // Re-sync: clear all slot state
                    memset(slots, 0, sizeof(slots));
                    for (int s = 0; s < MAX_SLOTS; s++) slots[s].tracking_id = -1;
                    frame_key_count = 0;
                    __android_log_print(ANDROID_LOG_WARN, TAG, "SYN_DROPPED — state reset");
                }
            }
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Event loop exited");
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_stopEventLoop(JNIEnv *env, jobject thiz) {
    g_running = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "stopEventLoop called");
}
