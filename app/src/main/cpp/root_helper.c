/*
 * root_helper — runs as root (via su), opens /dev/input/eventX and /dev/uinput,
 * then passes the fds back to the app via SCM_RIGHTS over a Unix socket.
 *
 * Protocol:
 *   App creates a Unix socket pair (or abstract socket), passes the path as argv[1].
 *   Helper connects, sends 2 fds: [evdev_fd, uinput_fd] via SCM_RIGHTS.
 *   Helper then exits.
 *
 * Usage: root_helper <socket_path> <evdev_path>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <linux/input.h>

static int send_fds(int sock, int *fds, int nfds) {
    char buf[1] = {0};
    struct iovec iov = { .iov_base = buf, .iov_len = 1 };

    size_t cmsg_space = CMSG_SPACE(sizeof(int) * nfds);
    char *cmsg_buf = calloc(1, cmsg_space);
    if (!cmsg_buf) return -1;

    struct msghdr msg = {
        .msg_iov        = &iov,
        .msg_iovlen     = 1,
        .msg_control    = cmsg_buf,
        .msg_controllen = cmsg_space,
    };
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type  = SCM_RIGHTS;
    cmsg->cmsg_len   = CMSG_LEN(sizeof(int) * nfds);
    memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * nfds);

    int ret = (int)sendmsg(sock, &msg, 0);
    free(cmsg_buf);
    return ret;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <socket_path> <evdev_path>\n", argv[0]);
        return 1;
    }
    const char *sock_path  = argv[1];
    const char *evdev_path = argv[2];

    /* Open the evdev device (needs root / SELinux bypass as root) */
    int evdev_fd = open(evdev_path, O_RDWR | O_NONBLOCK);
    if (evdev_fd < 0) {
        evdev_fd = open(evdev_path, O_RDONLY | O_NONBLOCK);
    }
    if (evdev_fd < 0) {
        fprintf(stderr, "open %s failed: %s\n", evdev_path, strerror(errno));
        return 2;
    }

    /* Grab the device so no one else receives events */
    if (ioctl(evdev_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "EVIOCGRAB failed: %s\n", strerror(errno));
        /* non-fatal — continue */
    }

    /* Open uinput */
    int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        fprintf(stderr, "open /dev/uinput failed: %s\n", strerror(errno));
        /* Send evdev_fd only; app will know uinput_fd = -1 */
        uinput_fd = -1;
    }

    /* Connect to the app's abstract Unix socket */
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        fprintf(stderr, "socket() failed: %s\n", strerror(errno));
        return 3;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace: first byte is '\0' */
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, sock_path, sizeof(addr.sun_path) - 2);
    socklen_t addr_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(sock_path);

    int retries = 20;
    while (retries-- > 0) {
        if (connect(sock, (struct sockaddr *)&addr, addr_len) == 0) break;
        usleep(100000); /* 100ms */
    }
    if (retries < 0) {
        fprintf(stderr, "connect failed: %s\n", strerror(errno));
        return 4;
    }

    /* Send both fds */
    int fds_to_send[2] = { evdev_fd, (uinput_fd >= 0 ? uinput_fd : evdev_fd) };
    /* We always send 2 fds; if uinput failed we send evdev twice and app detects it */
    send_fds(sock, fds_to_send, 2);

    /* Also send a flag byte: bit0 = evdev ok, bit1 = uinput ok */
    /* Already sent via iov in send_fds (1 byte = 0), just close */
    close(sock);

    /* Keep evdev and uinput open — they will live via the passed fds in the app process.
     * Close our copies here. The kernel keeps the file description alive. */
    close(evdev_fd);
    if (uinput_fd >= 0) close(uinput_fd);

    return 0;
}

