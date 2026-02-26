package com.fasa70.bettertouchpad

import kotlin.math.sqrt

// Linux input key codes
private const val BTN_LEFT  = 0x110
private const val BTN_RIGHT = 0x111

private const val TAP_MAX_MS         = 280L
private const val TAP_MAX_MOVE_PX    = 180
private const val DOUBLE_TAP_GAP_MS  = 280L

private enum class GestureState {
    IDLE, SINGLE_MOVING, DRAG, SCROLL, EDGE_SWIPE, THREE_FINGER
}

class GestureRecognizer(
    private val settings: SettingsRepository,
    private val mouseFd: Int,
    private val touchFd: Int,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private var state = GestureState.IDLE

    private var prevSlots  = Array(10) { SlotSnapshot(false, -1, 0, 0) }
    private var startSlots = Array(10) { SlotSnapshot(false, -1, 0, 0) }

    private var downTimeMs   = 0L
    private var lastTapUpMs  = 0L
    private var lastTapCount = 0

    private var nextTid = 100

    private var threeActiveIdx    = intArrayOf(0, 1, 2)
    // Centroid of the 3 fingers on the touchpad at gesture start
    private var threeCentroidPadX = 0
    private var threeCentroidPadY = 0

    private var accX = 0f
    private var accY = 0f

    // High-resolution scroll accumulators (in hi-res units; 120 hi-res = 1 scroll tick)
    private var scrollAccV = 0f
    private var scrollAccH = 0f

    private var edgeRight = false   // true = started from right/bottom edge

    data class SlotSnapshot(val active: Boolean, val trackingId: Int, val x: Int, val y: Int)

    /** Called from JNI on every SYN_REPORT. All arrays have [slotCount] entries. */
    @Suppress("unused")
    fun onFrame(
        slotActive: IntArray, trackingIds: IntArray,
        xs: IntArray, ys: IntArray, slotCount: Int
    ) {
        val s = settings.get()
        val now = System.currentTimeMillis()

        val cur = Array(slotCount) { i ->
            SlotSnapshot(slotActive[i] != 0, trackingIds[i], xs[i], ys[i])
        }
        val activeCount     = cur.count { it.active }
        val prevActiveCount = prevSlots.take(slotCount).count { it.active }
        val fingersAdded    = activeCount > prevActiveCount

        when {
            // ──── 0 fingers ────────────────────────────────────────────────
            activeCount == 0 -> {
                handleLift(prevActiveCount, now, s)
                state = GestureState.IDLE
                accX = 0f; accY = 0f
                scrollAccV = 0f; scrollAccH = 0f
            }

            // ──── 1 finger ─────────────────────────────────────────────────
            activeCount == 1 -> {
                val si = cur.indexOfFirst { it.active }

                when (state) {
                    GestureState.IDLE -> {
                        if (fingersAdded) {
                            downTimeMs = now
                            startSlots[si] = cur[si]
                            state = if (s.doubleTapDrag
                                && lastTapCount == 1
                                && (now - lastTapUpMs) < DOUBLE_TAP_GAP_MS) {
                                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, true)
                                GestureState.DRAG
                            } else {
                                GestureState.SINGLE_MOVING
                            }
                        }
                    }

                    GestureState.SINGLE_MOVING, GestureState.DRAG -> {
                        val p = prevSlots[si]
                        if (p.active && s.singleFingerMove) {
                            val dx = cur[si].x - p.x
                            val dy = cur[si].y - p.y
                            if (dx != 0 || dy != 0) {
                                accX += dx * s.cursorSensitivity
                                accY += dy * s.cursorSensitivity
                                val ix = accX.toInt(); val iy = accY.toInt()
                                if (ix != 0 || iy != 0) {
                                    NativeBridge.sendRelMove(mouseFd, ix, iy)
                                    accX -= ix; accY -= iy
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }

            // ──── 2 fingers ────────────────────────────────────────────────
            activeCount == 2 -> {
                val ai = cur.indices.filter { cur[it].active }
                val c0 = cur[ai[0]]; val c1 = cur[ai[1]]
                val p0 = prevSlots[ai[0]]; val p1 = prevSlots[ai[1]]

                when (state) {
                    GestureState.IDLE,
                    GestureState.SINGLE_MOVING,
                    GestureState.DRAG -> {
                        if (state == GestureState.DRAG)
                            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
                        downTimeMs = now
                        startSlots[ai[0]] = c0; startSlots[ai[1]] = c1

                        // Edge swipe detection: fingers near the physical left/right pad-X edge.
                        // Detection always uses pad-X regardless of swapAxes —
                        // swapAxes only affects which display axis the touch slides along.
                        val padMaxX = s.padMaxX.toFloat()
                        val edgePx  = (padMaxX * s.edgeThreshold).toInt()
                        val bothRight = c0.x > padMaxX - edgePx && c1.x > padMaxX - edgePx
                        val bothLeft  = c0.x < edgePx            && c1.x < edgePx

                        state = if (s.edgeSwipe && (bothRight || bothLeft)) {
                            edgeRight = bothRight
                            nextTid++
                            GestureState.EDGE_SWIPE
                        } else {
                            GestureState.SCROLL
                        }
                    }

                    GestureState.SCROLL -> {
                        if (p0.active && p1.active && s.twoFingerScroll) {
                            val avgDy = ((c0.y - p0.y) + (c1.y - p1.y)) / 2f
                            val avgDx = ((c0.x - p0.x) + (c1.x - p1.x)) / 2f
                            // 120 hi-res units = 1 scroll tick.
                            // Scale: at sensitivity=1.0, ~40 pad units of movement = 1 tick.
                            val hiResScale = s.scrollSensitivity * 3f
                            // Natural scroll: swipe down moves content down (same direction as finger).
                            // Traditional scroll: swipe down moves content up (negate).
                            val sign = if (s.naturalScroll) 1f else -1f
                            scrollAccV += avgDy * hiResScale * sign
                            scrollAccH += avgDx * hiResScale * sign

                            val hiV = scrollAccV.toInt(); scrollAccV -= hiV
                            val hiH = scrollAccH.toInt(); scrollAccH -= hiH
                            if (hiV != 0 || hiH != 0) {
                                NativeBridge.sendWheelHiRes(mouseFd, hiV, hiH)
                            }
                        }
                    }

                    GestureState.EDGE_SWIPE -> {
                        if (p0.active && p1.active && s.edgeSwipe) {
                            // uinput axis dimensions (match createTouchDevice call in TouchpadService):
                            //   swapAxes=false: uinputW=screenWidth,  uinputH=screenHeight
                            //   swapAxes=true:  uinputW=screenHeight, uinputH=screenWidth
                            // Meaning:
                            //   uinput_X axis (range 0..uinputW-1) = display_X when !swapAxes,
                            //                                         display_Y when  swapAxes
                            //   uinput_Y axis (range 0..uinputH-1) = display_Y when !swapAxes,
                            //                                         display_X when  swapAxes
                            //
                            // pad_X always maps to display_X in both cases:
                            //   !swapAxes: pad_X → display_X = uinput_X
                            //    swapAxes: pad_X → display_X = uinput_Y
                            //
                            // Goal: touch slides along display_X (inward from edge),
                            //       display_Y fixed at screen center.

                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth  else screenHeight

                            // display_X from pad_X (the sliding/inward axis)
                            var dispX = ((c0.x + c1.x) / 2f / s.padMaxX * screenWidth)
                                .toInt().coerceIn(0, screenWidth - 1)
                            if (s.invertX) dispX = screenWidth - 1 - dispX

                            // display_Y fixed at vertical screen center
                            val dispY = screenHeight / 2

                            // Convert display → uinput
                            val uiX: Int   // goes into ABS_MT_POSITION_X, range 0..uinputW-1
                            val uiY: Int   // goes into ABS_MT_POSITION_Y, range 0..uinputH-1
                            if (!s.swapAxes) {
                                // uinput_X = display_X, uinput_Y = display_Y
                                uiX = dispX
                                uiY = dispY.coerceIn(0, uinputH - 1)
                            } else {
                                // uinput_X = display_Y, uinput_Y = display_X
                                // display_Y center (screenHeight/2) must fit in uinputW (=screenHeight) range
                                uiX = dispY.coerceIn(0, uinputW - 1)
                                uiY = dispX.coerceIn(0, uinputH - 1)
                            }

                            val pts = intArrayOf(0, uiX, uiY, nextTid)
                            NativeBridge.injectTouch(touchFd, pts, 1)
                        }
                    }

                    else -> {}
                }
            }

            // ──── 3+ fingers ───────────────────────────────────────────────
            activeCount >= 3 -> {
                val ai = cur.indices.filter { cur[it].active }.take(3)

                when (state) {
                    GestureState.IDLE,
                    GestureState.SINGLE_MOVING,
                    GestureState.DRAG,
                    GestureState.SCROLL,
                    GestureState.EDGE_SWIPE -> {
                        if (state == GestureState.DRAG)
                            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
                        if (state == GestureState.EDGE_SWIPE)
                            NativeBridge.releaseAllTouches(touchFd, 1)

                        state = GestureState.THREE_FINGER
                        downTimeMs = now
                        nextTid++
                        threeActiveIdx = ai.toIntArray()

                        // Record centroid pad position at gesture start
                        threeCentroidPadX = ai.sumOf { cur[it].x } / 3
                        threeCentroidPadY = ai.sumOf { cur[it].y } / 3

                        // 3 virtual touch points spread ±100px around uinput center.
                        // uinput axis dimensions depend on swapAxes (see createTouchDevice call):
                        //   swapAxes=false: uinputW=screenWidth,  uinputH=screenHeight
                        //   swapAxes=true:  uinputW=screenHeight, uinputH=screenWidth
                        if (s.threeFingerMove) {
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth  else screenHeight
                            val pts = IntArray(4 * 3)
                            for (i in 0..2) {
                                pts[i*4+0] = i
                                pts[i*4+1] = uinputW / 2 + (i - 1) * 100
                                pts[i*4+2] = uinputH / 2
                                pts[i*4+3] = nextTid + i
                            }
                            NativeBridge.injectTouch(touchFd, pts, 3)
                        }
                    }

                    GestureState.THREE_FINGER -> {
                        if (s.threeFingerMove) {
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth  else screenHeight

                            // Compute centroid of current 3 finger positions
                            val curCentX = threeActiveIdx.sumOf { cur[it].x } / 3
                            val curCentY = threeActiveIdx.sumOf { cur[it].y } / 3

                            // Pad delta → display delta
                            val sp = s.touchInjectSpeed
                            var dispDx = (( curCentX - threeCentroidPadX).toFloat() / s.padMaxX * screenWidth  * sp).toInt()
                            var dispDy = (( curCentY - threeCentroidPadY).toFloat() / s.padMaxY * screenHeight * sp).toInt()

                            // Apply display-space inversion
                            if (s.invertX) dispDx = -dispDx
                            if (s.invertY) dispDy = -dispDy

                            // Convert display delta → uinput delta
                            val uiDx = if (!s.swapAxes) dispDx else dispDy
                            val uiDy = if (!s.swapAxes) dispDy else dispDx

                            // Move all 3 base points together by the same delta
                            val pts = IntArray(4 * 3)
                            for (i in 0..2) {
                                val finalX = (uinputW / 2 + (i - 1) * 100 + uiDx).coerceIn(0, uinputW - 1)
                                val finalY = (uinputH / 2 + uiDy).coerceIn(0, uinputH - 1)
                                pts[i*4+0] = i
                                pts[i*4+1] = finalX
                                pts[i*4+2] = finalY
                                pts[i*4+3] = nextTid + i
                            }
                            NativeBridge.injectTouch(touchFd, pts, 3)
                        }
                    }

                    else -> {}
                }
            }
        }

        // Save current frame as previous
        for (i in cur.indices) prevSlots[i] = cur[i]
    }

    /** Handle lifting all fingers — decide if it was a tap. */
    private fun handleLift(prevActiveCount: Int, now: Long, s: TouchpadSettings) {
        val duration = now - downTimeMs

        when (state) {
            GestureState.DRAG -> {
                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
            }
            GestureState.SINGLE_MOVING -> {
                if (s.singleFingerTap && prevActiveCount == 1 && duration < TAP_MAX_MS) {
                    val si = prevSlots.indexOfFirst { it.active }
                    if (si >= 0) {
                        val dx = prevSlots[si].x - startSlots[si].x
                        val dy = prevSlots[si].y - startSlots[si].y
                        if (sqrt((dx * dx + dy * dy).toDouble()) < TAP_MAX_MOVE_PX) {
                            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, true)
                            Thread.sleep(16)
                            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
                            lastTapUpMs  = now
                            lastTapCount = 1
                        }
                    }
                }
            }
            GestureState.SCROLL -> {
                if (s.twoFingerTap && prevActiveCount == 2 && duration < TAP_MAX_MS) {
                    val ai = prevSlots.indices.filter { prevSlots[it].active }.take(2)
                    val noMove = ai.all { i ->
                        val dx = prevSlots[i].x - startSlots[i].x
                        val dy = prevSlots[i].y - startSlots[i].y
                        sqrt((dx * dx + dy * dy).toDouble()) < TAP_MAX_MOVE_PX
                    }
                    if (noMove) {
                        NativeBridge.sendMouseButton(mouseFd, BTN_RIGHT, true)
                        Thread.sleep(16)
                        NativeBridge.sendMouseButton(mouseFd, BTN_RIGHT, false)
                    }
                }
            }
            GestureState.EDGE_SWIPE -> {
                NativeBridge.releaseAllTouches(touchFd, 1)
            }
            GestureState.THREE_FINGER -> {
                NativeBridge.releaseAllTouches(touchFd, 3)
            }
            else -> {}
        }
    }

    /** Called from JNI when a EV_KEY event arrives. */
    @Suppress("unused")
    fun onKeyEvent(code: Int, value: Int) {
        val s = settings.get()
        // BTN_MOUSE = BTN_LEFT = 0x110 is the physical touchpad click
        if (s.physicalClick && code == BTN_LEFT) {
            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, value != 0)
        }
    }
}
