package com.fasa70.bettertouchpad

import android.util.Log
import com.fasa70.bettertouchpad.system.ThreeFingerActionAdapter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// Linux input key codes
private const val BTN_LEFT  = 0x110
private const val BTN_RIGHT = 0x111
private const val BTN_MIDDLE = 0x112
private const val TAG = "GestureRecognizer"

private const val TAP_MAX_MS         = 280L
private const val TAP_MAX_MOVE_PX    = 180
private const val THREE_FINGER_DELAY_MS = 100L
private const val THREE_FINGER_MOVE_THRESHOLD_PX = 40

private enum class GestureState {
    IDLE, SINGLE_MOVING, DRAG, SCROLL, EDGE_SWIPE, THREE_FINGER, PINCH_ZOOM, TOP_SWIPE
}

class GestureRecognizer(
    private val settings: SettingsRepository,
    private val mouseFd: Int,
    private val touchFd: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val threeFingerAdapter: ThreeFingerActionAdapter
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
    // After finishing a three-finger gesture on finger-drop, ignore residual contacts
    // until all fingers lift to avoid accidental 2-finger gesture re-entry.
    private var suppressResidualAfterThreeFinger = false

    private var nextTid = 100

    private var threeActiveIdx = intArrayOf(0, 1, 2)
    // Centroid of the 3 fingers on the touchpad at gesture start
    private var threeCentroidPadX = 0
    private var threeCentroidPadY = 0

    private var accX = 0f
    private var accY = 0f

    // High-resolution scroll accumulators (in hi-res units; 120 hi-res = 1 scroll tick)
    private var scrollAccV = 0f
    private var scrollAccH = 0f

    private var edgeRight = false // true = started from right edge
    // Fixed edge-swipe injection point in uinput coordinates (set when gesture starts)
    private var edgeFixedUiX = 0
    private var edgeFixedUiY = 0

    // Pinch/zoom state tracking
    private var pinchInitialDistance = 0f
    private var pinchCurrentPadX0 = 0; private var pinchCurrentPadY0 = 0
    private var pinchCurrentPadX1 = 0; private var pinchCurrentPadY1 = 0
    private var pinchTrackingId0 = 0; private var pinchTrackingId1 = 0

    // Two-finger top swipe state
    private var topSwipeSide = false // true = right (控制中心), false = left (通知中心)
    private var topSwipeTrackingId = 0
    private var topSwipeDispX = 0       // anchor screen X
    private var topSwipeDispY = 0       // anchor screen Y
    private var topSwipeStartPadY = 0   // pad Y at gesture start
    private var topSwipeTouchInjected = false

    private enum class ThreeFingerFinalizeReason {
        FINGER_DROP,
        ALL_FINGERS_UP
    }

    // Three-finger delayed injection state
    private var threeFingerDownTimeMs: Long = 0L
    private var threeFingerInjectionStarted = false

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
        val activeCount = cur.count { it.active }
        val prevActiveCount = prevSlots.take(slotCount).count { it.active }
        val fingersAdded = activeCount > prevActiveCount

        maybeFinalizeThreeFingerOnFingerDrop(activeCount, prevActiveCount, now, s)

        if (suppressResidualAfterThreeFinger && activeCount > 0) {
            Log.d(TAG, "suppress residual contacts after three-finger: active=$activeCount")
            for (i in cur.indices) prevSlots[i] = cur[i]
            return
        }

        when {
            // ──── 0 fingers ────────────────────────────────────────────────
            activeCount == 0 -> {
                handleLift(prevActiveCount, now, s)
                state = GestureState.IDLE
                accX = 0f; accY = 0f
                scrollAccV = 0f; scrollAccH = 0f
                trailingAfterScroll = false
                suppressResidualAfterThreeFinger = false
                threeFingerInjectionStarted = false
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
                                && (now - firstTapUpMs) < s.doubleTapIntervalMs
                            ) {
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

                    GestureState.PINCH_ZOOM -> {
                        releasePinchTouches()
                        trailingAfterScroll = true
                        state = GestureState.SINGLE_MOVING
                    }

                    GestureState.TOP_SWIPE -> {
                        NativeBridge.releaseAllTouches(touchFd, 1)
                        state = GestureState.IDLE
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
                        val edgePx = (padMaxX * s.edgeThreshold).toInt()
                        val bothRight = c0.x > padMaxX - edgePx && c1.x > padMaxX - edgePx
                        val bothLeft = c0.x < edgePx && c1.x < edgePx

                        if (s.edgeSwipe && (bothRight || bothLeft)) {
                            edgeRight = bothRight
                            nextTid++

                            // Compute and store the FIXED uinput injection start point now.
                            // The touch slides along display_X (inward from edge),
                            // display_Y is fixed at screen vertical center.
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth else screenHeight

                            // display_X: start at the screen edge (0 for left, screenWidth-1 for right)
                            val dispX = if (bothRight) {
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
                        } else if (s.twoFingerTopSwipe) {
                            // Top edge swipe: fingers near the physical top pad-Y edge.
                            val topPx = (s.padMaxY * s.topEdgeThreshold).toInt()
                            val oneInTop = c0.y < topPx || c1.y < topPx

                            if (oneInTop) {
                                // Horizontal zone: left zone or right zone
                                val zonePx = (s.padMaxX * s.topEdgeZoneRatio).toInt()
                                val inLeft = c0.x < zonePx || c1.x < zonePx
                                val inRight = c0.x >= s.padMaxX - zonePx || c1.x >= s.padMaxX - zonePx

                                // Left takes priority
                                if (inLeft) {
                                    startTopSwipe(c0, c1, false, s)
                                } else if (inRight) {
                                    startTopSwipe(c0, c1, true, s)
                                } else {
                                    state = GestureState.SCROLL
                                }
                            } else {
                                state = GestureState.SCROLL
                            }
                        } else {
                            state = GestureState.SCROLL
                        }
                    }

                    GestureState.SCROLL -> {
                        if (p0.active && p1.active) {
                            // Check for pinch-to-zoom
                            if (s.twoFingerZoom) {
                                val currentDist = sqrt(
                                    (c0.x - c1.x).toDouble().let { it * it } +
                                        (c0.y - c1.y).toDouble().let { it * it }
                                ).toFloat()
                                val initialDist = sqrt(
                                    (startSlots[ai[0]].x - startSlots[ai[1]].x).toDouble()
                                        .let { it * it } +
                                        (startSlots[ai[0]].y - startSlots[ai[1]].y).toDouble()
                                            .let { it * it }
                                ).toFloat()

                                val dx0 = c0.x - startSlots[ai[0]].x
                                val dy0 = c0.y - startSlots[ai[0]].y
                                val dx1 = c1.x - startSlots[ai[1]].x
                                val dy1 = c1.y - startSlots[ai[1]].y
                                val avgDx = (dx0 + dx1) / 2f
                                val avgDy = (dy0 + dy1) / 2f
                                val moveDelta = sqrt(avgDx * avgDx + avgDy * avgDy)
                                val distanceDelta = abs(currentDist - initialDist)

                                // Finger movement direction: dot product of per-frame displacements
                                val fdx0 = c0.x - p0.x
                                val fdy0 = c0.y - p0.y
                                val fdx1 = c1.x - p1.x
                                val fdy1 = c1.y - p1.y
                                val dotProduct = fdx0 * fdx1 + fdy0 * fdy1
                                val isOpposing = dotProduct < 0

                                if (isOpposing && distanceDelta > s.minPinchDistance && distanceDelta / max(moveDelta, 1.0f) > s.zoomSensitivity) {
                                    enterPinchZoom(c0, c1, initialDist, s)
                                    pinchCurrentPadX0 = c0.x; pinchCurrentPadY0 = c0.y
                                    pinchCurrentPadX1 = c1.x; pinchCurrentPadY1 = c1.y
                                    injectPinchTouch(s)
                                    // Skip scroll for this frame
                                    for (i in cur.indices) prevSlots[i] = cur[i]
                                    return@onFrame
                                }
                            }

                            // Normal scroll handling
                            if (s.twoFingerScroll) {
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
                    }

                    GestureState.EDGE_SWIPE -> {
                        if (p0.active && p1.active && s.edgeSwipe) {
                            val uinputW = if (s.swapAxes) screenHeight else screenWidth
                            val uinputH = if (s.swapAxes) screenWidth else screenHeight

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

                    GestureState.PINCH_ZOOM -> {
                        if (p0.active && p1.active && s.twoFingerZoom) {
                            pinchCurrentPadX0 = c0.x; pinchCurrentPadY0 = c0.y
                            pinchCurrentPadX1 = c1.x; pinchCurrentPadY1 = c1.y
                            injectPinchTouch(s)
                        }
                    }

                    GestureState.TOP_SWIPE -> {
                        if (p0.active && p1.active && s.twoFingerTopSwipe) {
                            val avgPadY = (c0.y + c1.y) / 2
                            val deltaY = avgPadY - topSwipeStartPadY

                            if (deltaY >= 10) {
                                val uinputW = if (s.swapAxes) screenHeight else screenWidth
                                val uinputH = if (s.swapAxes) screenWidth else screenHeight

                                // Compute display coordinates (screen space), apply invertX/invertY
                                val ratio = deltaY.toFloat() / s.padMaxY
                                var dispY = (ratio * screenHeight).toInt().coerceIn(0, screenHeight - 1)
                                if (s.invertY) dispY = screenHeight - 1 - dispY
                                var dispX = topSwipeDispX
                                if (s.invertX) dispX = screenWidth - 1 - dispX

                                // Convert display (screen) -> uinput coordinates, same as EDGE_SWIPE
                                val touchX = if (!s.swapAxes) dispX else dispY.coerceIn(0, uinputW - 1)
                                val touchY = if (!s.swapAxes) dispY.coerceIn(0, uinputH - 1) else dispX.coerceIn(0, uinputH - 1)

                                val pts = intArrayOf(0, touchX, touchY, topSwipeTrackingId)
                                NativeBridge.injectTouch(touchFd, pts, 1)
                                topSwipeTouchInjected = true
                            }
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
                    GestureState.EDGE_SWIPE,
                    GestureState.PINCH_ZOOM -> {
                        enterThreeFingerGesture(cur, ai, now, s)
                    }

                    GestureState.THREE_FINGER -> {
                        updateThreeFingerGesture(cur, now, s)
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
                    && prevActiveCount == 1 && duration < TAP_MAX_MS
                ) {
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

            GestureState.PINCH_ZOOM -> {
                releasePinchTouches()
            }

            GestureState.TOP_SWIPE -> {
                NativeBridge.releaseAllTouches(touchFd, 1)
            }

            GestureState.THREE_FINGER -> {
                if (threeFingerInjectionStarted) {
                    threeFingerAdapter.onGestureEnd(s)
                }
                maybeFireThreeFingerMiddleClick(s, prevActiveCount, duration, ThreeFingerFinalizeReason.ALL_FINGERS_UP)
            }

            else -> {}
        }
    }

    private fun maybeFinalizeThreeFingerOnFingerDrop(
        activeCount: Int,
        prevActiveCount: Int,
        now: Long,
        s: TouchpadSettings
    ) {
        if (state != GestureState.THREE_FINGER || activeCount >= 3) return

        val duration = now - downTimeMs
        Log.d(TAG, "three-finger finalize on drop: prev=$prevActiveCount, duration=$duration")
        if (threeFingerInjectionStarted) {
            threeFingerAdapter.onGestureEnd(s)
        }
        maybeFireThreeFingerMiddleClick(s, prevActiveCount, duration, ThreeFingerFinalizeReason.FINGER_DROP)
        state = GestureState.IDLE
        trailingAfterScroll = false
        suppressResidualAfterThreeFinger = true
    }

    private fun enterThreeFingerGesture(
        cur: Array<SlotSnapshot>,
        activeIdx: List<Int>,
        now: Long,
        s: TouchpadSettings
    ) {
        if (state == GestureState.DRAG) {
            NativeBridge.sendMouseButton(mouseFd, BTN_LEFT, false)
        }
        if (state == GestureState.EDGE_SWIPE) {
            NativeBridge.releaseAllTouches(touchFd, 1)
        }
        if (state == GestureState.PINCH_ZOOM) {
            releasePinchTouches()
        }
        if (state == GestureState.TOP_SWIPE) {
            NativeBridge.releaseAllTouches(touchFd, 1)
        }

        pendingFirstTap = false
        state = GestureState.THREE_FINGER
        downTimeMs = now
        threeFingerDownTimeMs = now
        threeFingerInjectionStarted = false
        nextTid++
        threeActiveIdx = activeIdx.toIntArray()
        threeCentroidPadX = activeIdx.sumOf { cur[it].x } / 3
        threeCentroidPadY = activeIdx.sumOf { cur[it].y } / 3
    }

    private fun updateThreeFingerGesture(cur: Array<SlotSnapshot>, now: Long, s: TouchpadSettings) {
        if (!threeFingerInjectionStarted) {
            val duration = now - threeFingerDownTimeMs
            val rawDisp = computeThreeFingerRawDisp(cur, s)
            val move = sqrt((rawDisp.first * rawDisp.first + rawDisp.second * rawDisp.second).toDouble())
            if (duration >= THREE_FINGER_DELAY_MS || move > THREE_FINGER_MOVE_THRESHOLD_PX) {
                threeFingerAdapter.onGestureStart(s, nextTid)
                threeFingerInjectionStarted = true
            }
        }
        if (threeFingerInjectionStarted) {
            val rawDisp = computeThreeFingerRawDisp(cur, s)
            threeFingerAdapter.onGestureMove(s, now, rawDisp.first, rawDisp.second)
        }
    }

    private fun computeThreeFingerRawDisp(cur: Array<SlotSnapshot>, s: TouchpadSettings): Pair<Int, Int> {
        val curCentX = threeActiveIdx.sumOf { cur[it].x } / 3
        val curCentY = threeActiveIdx.sumOf { cur[it].y } / 3
        val rawDispDx = ((curCentX - threeCentroidPadX).toFloat() / s.padMaxX * screenWidth).toInt()
        val rawDispDy = ((curCentY - threeCentroidPadY).toFloat() / s.padMaxY * screenHeight).toInt()
        return Pair(rawDispDx, rawDispDy)
    }

    private fun maybeFireThreeFingerMiddleClick(
        s: TouchpadSettings,
        prevActiveCount: Int,
        duration: Long,
        reason: ThreeFingerFinalizeReason
    ) {
        val directionalConsumed = threeFingerAdapter.consumedAsDirectionalAction()
        if (!s.threeFingerMiddleClick || prevActiveCount < 3 || duration >= TAP_MAX_MS || directionalConsumed) {
            Log.d(
                TAG,
                "three-finger middle skipped: reason=$reason enabled=${s.threeFingerMiddleClick} prev=$prevActiveCount duration=$duration consumed=$directionalConsumed"
            )
            return
        }

        val ai = prevSlots.indices.filter { prevSlots[it].active }.take(3)
        if (ai.size != 3) {
            Log.d(TAG, "three-finger middle skipped: reason=$reason invalid-active-size=${ai.size}")
            return
        }

        val endCentX = ai.sumOf { prevSlots[it].x } / 3
        val endCentY = ai.sumOf { prevSlots[it].y } / 3
        val rawDispDx = ((endCentX - threeCentroidPadX).toFloat() / s.padMaxX * screenWidth).toInt()
        val rawDispDy = ((endCentY - threeCentroidPadY).toFloat() / s.padMaxY * screenHeight).toInt()
        val move = sqrt((rawDispDx * rawDispDx + rawDispDy * rawDispDy).toDouble())
        if (move >= TAP_MAX_MOVE_PX) {
            Log.d(TAG, "three-finger middle skipped: reason=$reason move=$move")
            return
        }

        Log.d(TAG, "three-finger middle fired: reason=$reason move=$move")
        NativeBridge.sendMouseButton(mouseFd, BTN_MIDDLE, true)
        Thread.sleep(16)
        NativeBridge.sendMouseButton(mouseFd, BTN_MIDDLE, false)
    }

    private fun padToTouchX(padX: Int, s: TouchpadSettings): Int {
        val uinputW = if (s.swapAxes) screenHeight else screenWidth
        var tx = (padX.toFloat() / s.padMaxX * uinputW).toInt()
        if (s.invertX) tx = uinputW - 1 - tx
        return tx.coerceIn(0, uinputW - 1)
    }

    private fun padToTouchY(padY: Int, s: TouchpadSettings): Int {
        val uinputH = if (s.swapAxes) screenWidth else screenHeight
        var ty = (padY.toFloat() / s.padMaxY * uinputH).toInt()
        if (s.invertY) ty = uinputH - 1 - ty
        return ty.coerceIn(0, uinputH - 1)
    }

    private fun injectPinchTouch(s: TouchpadSettings) {
        val tx0 = padToTouchX(pinchCurrentPadX0, s)
        val ty0 = padToTouchY(pinchCurrentPadY0, s)
        val tx1 = padToTouchX(pinchCurrentPadX1, s)
        val ty1 = padToTouchY(pinchCurrentPadY1, s)
        val pts = intArrayOf(
            0, tx0, ty0, pinchTrackingId0,
            1, tx1, ty1, pinchTrackingId1
        )
        NativeBridge.injectTouch(touchFd, pts, 2)
    }

    private fun releasePinchTouches() {
        NativeBridge.releaseAllTouches(touchFd, 2)
    }

    private fun enterPinchZoom(
        c0: SlotSnapshot, c1: SlotSnapshot,
        initialDist: Float, s: TouchpadSettings
    ) {
        nextTid++
        pinchTrackingId0 = nextTid
        nextTid++
        pinchTrackingId1 = nextTid
        pinchInitialDistance = initialDist
        pinchCurrentPadX0 = c0.x; pinchCurrentPadY0 = c0.y
        pinchCurrentPadX1 = c1.x; pinchCurrentPadY1 = c1.y
        state = GestureState.PINCH_ZOOM
        injectPinchTouch(s)
    }

    private fun startTopSwipe(c0: SlotSnapshot, c1: SlotSnapshot, isRight: Boolean, s: TouchpadSettings) {
        nextTid++
        topSwipeTrackingId = nextTid
        topSwipeSide = isRight
        topSwipeStartPadY = (c0.y + c1.y) / 2

        // Injection anchor in screen coordinates: (10, 0) for left, (screenWidth-11, 0) for right
        topSwipeDispX = if (isRight) screenWidth - 11 else 10
        topSwipeDispY = 0
        topSwipeTouchInjected = false

        state = GestureState.TOP_SWIPE
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
