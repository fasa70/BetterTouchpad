package com.fasa70.bettertouchpad

import kotlin.math.sqrt

// Linux input key codes
private const val BTN_LEFT  = 0x110
private const val BTN_RIGHT = 0x111

private const val TAP_MAX_MS         = 280L
private const val TAP_MAX_MOVE_PX    = 180

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
    // Time of first-tap lift (used for double-tap drag detection)
    private var firstTapUpMs = 0L
    // Whether we have recorded a pending first tap (waiting to see if 2nd tap comes)
    private var pendingFirstTap = false
    // Track whether a two-finger tap was validated (short duration, no movement)
    // so we can fire right-click when all fingers finally lift
    private var pendingTwoFingerTap = false
    // True when we transitioned from SCROLL to SINGLE_MOVING (trailing finger after scroll/tap)
    // — suppress cursor movement for this residual finger
    private var trailingAfterScroll = false

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

    private var edgeRight = false   // true = started from right edge
    // Fixed edge-swipe injection point in uinput coordinates (set when gesture starts)
    private var edgeFixedUiX = 0
    private var edgeFixedUiY = 0

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
                trailingAfterScroll = false
            }

            // ──── 1 finger ─────────────────────────────────────────────────
            activeCount == 1 -> {
                val si = cur.indexOfFirst { it.active }

                when (state) {
                    GestureState.IDLE -> {
                        if (fingersAdded) {
                            downTimeMs = now
                            startSlots[si] = cur[si]
                            trailingAfterScroll = false
                            // Check if this is the 2nd tap of a double-tap drag
                            state = if (s.doubleTapDrag
                                && pendingFirstTap
                                && (now - firstTapUpMs) < s.doubleTapIntervalMs) {
                                // 2nd tap: send left-down for drag (do NOT send click on 1st tap)
                                pendingFirstTap = false
                                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, true)
                                GestureState.DRAG
                            } else {
                                pendingFirstTap = false
                                GestureState.SINGLE_MOVING
                            }
                        }
                    }

                    GestureState.SCROLL -> {
                        // One finger lifted while in SCROLL — check for two-finger tap
                        if (s.twoFingerTap) {
                            val duration = now - downTimeMs
                            if (duration < TAP_MAX_MS) {
                                val ai2 = prevSlots.indices.filter { prevSlots[it].active }.take(2)
                                val noMove = ai2.size == 2 && ai2.all { i ->
                                    val dx = prevSlots[i].x - startSlots[i].x
                                    val dy = prevSlots[i].y - startSlots[i].y
                                    sqrt((dx * dx + dy * dy).toDouble()) < TAP_MAX_MOVE_PX
                                }
                                if (noMove) pendingTwoFingerTap = true
                            }
                        }
                        // Transition to single-finger state; suppress cursor movement
                        // until the remaining finger also lifts
                        trailingAfterScroll = true
                        state = GestureState.SINGLE_MOVING
                    }

                    GestureState.SINGLE_MOVING, GestureState.DRAG -> {
                        val p = prevSlots[si]
                        // Only move cursor if the slot was active last frame, feature is enabled,
                        // and we are not in the trailing finger state after a 2-finger gesture
                        val allowMove = p.active && s.singleFingerMove && !trailingAfterScroll
                        if (allowMove) {
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
                        // Cancel any pending first-tap when 2nd finger lands
                        pendingFirstTap = false
                        pendingTwoFingerTap = false
                        trailingAfterScroll = false
                        downTimeMs = now
                        startSlots[ai[0]] = c0; startSlots[ai[1]] = c1

                        // Edge swipe detection: fingers near the physical left/right pad-X edge.
                        val padMaxX = s.padMaxX.toFloat()
                        val edgePx  = (padMaxX * s.edgeThreshold).toInt()
                        val bothRight = c0.x > padMaxX - edgePx && c1.x > padMaxX - edgePx
                        val bothLeft  = c0.x < edgePx            && c1.x < edgePx

                        if (s.edgeSwipe && (bothRight || bothLeft)) {
                            edgeRight = bothRight
                            nextTid++

                            // Compute and store the FIXED uinput injection start point now.
                            // The touch slides along display_X (inward from edge),
                            // display_Y is fixed at screen vertical center.
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth  else screenHeight

                            // display_X: start at the screen edge (0 for left, screenWidth-1 for right)
                            var dispX = if (bothRight) {
                                if (s.invertX) 0 else screenWidth - 1
                            } else {
                                if (s.invertX) screenWidth - 1 else 0
                            }
                            val dispY = screenHeight / 2

                            // Convert display → uinput
                            edgeFixedUiX = if (!s.swapAxes) dispX else dispY.coerceIn(0, uinputW - 1)
                            edgeFixedUiY = if (!s.swapAxes) dispY.coerceIn(0, uinputH - 1) else dispX.coerceIn(0, uinputH - 1)

                            // Inject initial touch at fixed start point
                            val pts = intArrayOf(0, edgeFixedUiX, edgeFixedUiY, nextTid)
                            NativeBridge.injectTouch(touchFd, pts, 1)

                            state = GestureState.EDGE_SWIPE
                        } else {
                            state = GestureState.SCROLL
                        }
                    }

                    GestureState.SCROLL -> {
                        if (p0.active && p1.active && s.twoFingerScroll) {
                            val avgDy = ((c0.y - p0.y) + (c1.y - p1.y)) / 2f
                            val avgDx = ((c0.x - p0.x) + (c1.x - p1.x)) / 2f
                            val hiResScale = s.scrollSensitivity * 3f
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
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth  else screenHeight

                            // The sliding axis is display_X (inward from edge).
                            // Compute movement delta along display_X from finger movement on pad_X.
                            val avgPadX = (c0.x + c1.x) / 2f
                            val prevAvgPadX = (p0.x + p1.x) / 2f
                            val deltaPadX = avgPadX - prevAvgPadX

                            // Convert pad_X delta to display_X delta
                            var dispDx = (deltaPadX / s.padMaxX * screenWidth).toInt()
                            if (s.invertX) dispDx = -dispDx

                            // Update the current injection position along the sliding axis only
                            if (!s.swapAxes) {
                                // uinput_X = display_X (sliding axis)
                                edgeFixedUiX = (edgeFixedUiX + dispDx).coerceIn(0, uinputW - 1)
                                // uinput_Y = display_Y — stays fixed
                            } else {
                                // uinput_Y = display_X (sliding axis)
                                edgeFixedUiY = (edgeFixedUiY + dispDx).coerceIn(0, uinputH - 1)
                                // uinput_X = display_Y — stays fixed
                            }

                            val pts = intArrayOf(0, edgeFixedUiX, edgeFixedUiY, nextTid)
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

                        pendingFirstTap = false
                        state = GestureState.THREE_FINGER
                        downTimeMs = now
                        nextTid++
                        threeActiveIdx = ai.toIntArray()

                        // Record centroid pad position at gesture start
                        threeCentroidPadX = ai.sumOf { cur[it].x } / 3
                        threeCentroidPadY = ai.sumOf { cur[it].y } / 3

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

                            val curCentX = threeActiveIdx.sumOf { cur[it].x } / 3
                            val curCentY = threeActiveIdx.sumOf { cur[it].y } / 3

                            val sp = s.touchInjectSpeed
                            var dispDx = ((curCentX - threeCentroidPadX).toFloat() / s.padMaxX * screenWidth  * sp).toInt()
                            var dispDy = ((curCentY - threeCentroidPadY).toFloat() / s.padMaxY * screenHeight * sp).toInt()

                            if (s.invertX) dispDx = -dispDx
                            if (s.invertY) dispDy = -dispDy

                            val uiDx = if (!s.swapAxes) dispDx else dispDy
                            val uiDy = if (!s.swapAxes) dispDy else dispDx

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

        // Fire pending two-finger tap right-click if applicable
        // (this happens when one finger lifted first, tap was validated, and now last finger lifts)
        if (pendingTwoFingerTap) {
            pendingTwoFingerTap = false
            NativeBridge.sendMouseButton(mouseFd, BTN_RIGHT, true)
            Thread.sleep(16)
            NativeBridge.sendMouseButton(mouseFd, BTN_RIGHT, false)
            return
        }

        when (state) {
            GestureState.DRAG -> {
                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
            }
            GestureState.SINGLE_MOVING -> {
                // Only treat as a single-finger tap if this was a real single-finger gesture
                // (not a trailing finger after scroll, which is now tracked by trailingAfterScroll)
                if (!trailingAfterScroll && s.singleFingerTap
                    && prevActiveCount == 1 && duration < TAP_MAX_MS) {
                    val si = prevSlots.indexOfFirst { it.active }
                    if (si >= 0) {
                        val dx = prevSlots[si].x - startSlots[si].x
                        val dy = prevSlots[si].y - startSlots[si].y
                        if (sqrt((dx * dx + dy * dy).toDouble()) < TAP_MAX_MOVE_PX) {
                            if (s.doubleTapDrag && !pendingFirstTap) {
                                // Record first tap — do NOT send click yet.
                                pendingFirstTap = true
                                firstTapUpMs = now
                                val capturedUpMs = firstTapUpMs
                                Thread {
                                    Thread.sleep(s.doubleTapIntervalMs.toLong())
                                    if (pendingFirstTap && firstTapUpMs == capturedUpMs) {
                                        pendingFirstTap = false
                                        NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, true)
                                        Thread.sleep(16)
                                        NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
                                    }
                                }.start()
                            } else if (!s.doubleTapDrag) {
                                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, true)
                                Thread.sleep(16)
                                NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
                            }
                        }
                    }
                }
            }
            GestureState.SCROLL -> {
                // Both fingers lifted simultaneously without going through the 1-finger transition
                if (s.twoFingerTap && prevActiveCount == 2 && duration < TAP_MAX_MS) {
                    val ai = prevSlots.indices.filter { prevSlots[it].active }.take(2)
                    val noMove = ai.size == 2 && ai.all { i ->
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
        if (s.physicalClick && code == BTN_LEFT) {
            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, value != 0)
        }
    }
}
