package com.fasa70.bettertouchpad

/**
 * JNI bridge to native C code.
 * All methods require root privileges for the open/grab/uinput calls.
 */
object NativeBridge {

    init {
        System.loadLibrary("touchpad_jni")
    }

    // --- Device open/grab (direct, requires chmod 666 first) ---
    external fun openDevice(path: String): Int
    external fun grabDevice(fd: Int): Boolean
    external fun ungrabDevice(fd: Int)
    external fun closeDevice(fd: Int)

    // --- Root helper socket (SCM_RIGHTS fd passing) ---
    /** Create an abstract Unix socket server; returns server_fd */
    external fun createHelperSocket(socketName: String): Int
    /** Block until helper connects and sends 2 fds; returns [evdevFd, uinputFd] or null */
    external fun receiveFdsFromHelper(serverFd: Int, timeoutMs: Int): IntArray?

    // --- Event loop (blocking, call on background thread) ---
    /** Set the GestureRecognizer instance as callback before startEventLoop */
    external fun setCallback(callback: Any)
    /** Blocking event loop; returns when stopEventLoop() is called */
    external fun startEventLoop(fd: Int)
    external fun stopEventLoop()

    // --- Virtual mouse (uinput) ---
    external fun createMouseDevice(): Int
    external fun sendRelMove(fd: Int, dx: Int, dy: Int)
    external fun sendWheel(fd: Int, v: Int, h: Int)
    /** High-resolution wheel: v/h are hi-res units (120 = one detent). Also emits integer ticks. */
    external fun sendWheelHiRes(fd: Int, v: Int, h: Int)
    /** btn: BTN_LEFT=0x110, BTN_RIGHT=0x111 */
    external fun sendMouseButton(fd: Int, btn: Int, down: Boolean)
    external fun destroyMouseDevice(fd: Int)

    // --- Virtual touch (uinput) ---
    external fun createTouchDevice(screenWidth: Int, screenHeight: Int): Int
    /** points: flat array [slot, x, y, trackingId] * count */
    external fun injectTouch(fd: Int, points: IntArray, count: Int)
    external fun releaseAllTouches(fd: Int, count: Int)
    external fun destroyTouchDevice(fd: Int)
}
