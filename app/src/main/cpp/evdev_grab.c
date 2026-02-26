#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <linux/input.h>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <stddef.h>
#include <stdlib.h>

#define TAG "evdev_grab"

JNIEXPORT jint JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_openDevice(JNIEnv *env, jobject thiz, jstring path) {
    const char *dev_path = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(dev_path, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        // fallback to read-only
        fd = open(dev_path, O_RDONLY | O_NONBLOCK);
    }
    (*env)->ReleaseStringUTFChars(env, path, dev_path);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "openDevice failed: %s", strerror(errno));
        return -1;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "openDevice fd=%d", fd);
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_grabDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "EVIOCGRAB failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Device grabbed fd=%d", fd);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_ungrabDevice(JNIEnv *env, jobject thiz, jint fd) {
    ioctl(fd, EVIOCGRAB, 0);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Device ungrabbed fd=%d", fd);
}

JNIEXPORT void JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_closeDevice(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) {
        close(fd);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Device closed fd=%d", fd);
    }
}

/**
 * Create an abstract Unix socket server and return its fd.
 * The helper process will connect to this socket and send evdev/uinput fds.
 * socketName: abstract name (without leading \0)
 */
JNIEXPORT jint JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_createHelperSocket(JNIEnv *env, jobject thiz, jstring socketName) {
    const char *name = (*env)->GetStringUTFChars(env, socketName, NULL);

    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "socket() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, socketName, name);
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; /* abstract namespace */
    strncpy(addr.sun_path + 1, name, sizeof(addr.sun_path) - 2);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));

    if (bind(server_fd, (struct sockaddr *)&addr, addr_len) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "bind() failed: %s", strerror(errno));
        close(server_fd);
        (*env)->ReleaseStringUTFChars(env, socketName, name);
        return -1;
    }
    if (listen(server_fd, 1) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "listen() failed: %s", strerror(errno));
        close(server_fd);
        (*env)->ReleaseStringUTFChars(env, socketName, name);
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Helper socket created: @%s fd=%d", name, server_fd);
    (*env)->ReleaseStringUTFChars(env, socketName, name);
    return server_fd;
}

/**
 * Accept a connection on serverFd, receive 2 fds via SCM_RIGHTS.
 * Returns jintArray of [evdev_fd, uinput_fd], or null on failure.
 * timeoutMs: how long to wait for the helper to connect.
 */
JNIEXPORT jintArray JNICALL
Java_com_fasa70_bettertouchpad_NativeBridge_receiveFdsFromHelper(JNIEnv *env, jobject thiz, jint server_fd, jint timeoutMs) {
    // Set timeout on accept via SO_RCVTIMEO
    struct timeval tv;
    tv.tv_sec  = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
    setsockopt(server_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    int client_fd = accept(server_fd, NULL, NULL);
    if (client_fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "accept() failed: %s", strerror(errno));
        return NULL;
    }

    // Receive SCM_RIGHTS message with 2 fds
    char buf[1];
    struct iovec iov = { .iov_base = buf, .iov_len = 1 };

    size_t cmsg_space = CMSG_SPACE(sizeof(int) * 2);
    char *cmsg_buf = calloc(1, cmsg_space);
    if (!cmsg_buf) {
        close(client_fd);
        return NULL;
    }

    struct msghdr msg = {
        .msg_iov        = &iov,
        .msg_iovlen     = 1,
        .msg_control    = cmsg_buf,
        .msg_controllen = cmsg_space,
    };

    ssize_t nrecv = recvmsg(client_fd, &msg, 0);
    close(client_fd);

    if (nrecv < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "recvmsg() failed: %s", strerror(errno));
        free(cmsg_buf);
        return NULL;
    }

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_type != SCM_RIGHTS) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "No SCM_RIGHTS received");
        free(cmsg_buf);
        return NULL;
    }

    int received_fds[2] = { -1, -1 };
    int nfds = (int)((cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int));
    if (nfds >= 1) received_fds[0] = ((int *)CMSG_DATA(cmsg))[0];
    if (nfds >= 2) received_fds[1] = ((int *)CMSG_DATA(cmsg))[1];
    free(cmsg_buf);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Received fds: evdev=%d uinput=%d", received_fds[0], received_fds[1]);

    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, received_fds);
    return result;
}
