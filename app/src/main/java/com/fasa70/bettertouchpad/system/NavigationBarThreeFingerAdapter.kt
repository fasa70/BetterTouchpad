package com.fasa70.bettertouchpad.system

import android.util.Log
import com.fasa70.bettertouchpad.NativeBridge
import com.fasa70.bettertouchpad.TouchpadSettings
import kotlin.math.abs

private enum class ThreeDir {
    UP, DOWN, LEFT, RIGHT
}

private const val TAG = "ThreeFingerNavAdapter"
private const val DIRECTION_LOCK_THRESHOLD_PX = 90
private const val NAV_START_Y_PX = 12
private const val HORIZONTAL_STEP_DIVISOR = 3

class NavigationBarThreeFingerAdapter(
    private val touchFd: Int,
    private val screenWidth: Int,
    private val screenHeight: Int
) : ThreeFingerActionAdapter {

    private var trackingBase = 0
    private var direction: ThreeDir? = null
    private var consumed = false

    private var navInjected = false
    private var navUiX = 0
    private var navUiY = 0
    private var navStartDispX = 0
    private var navStartDispY = 0

    private var downModeInjected = false

    override fun onGestureStart(settings: TouchpadSettings, trackingBase: Int) {
        this.trackingBase = trackingBase
        direction = null
        consumed = false
        navInjected = false
        downModeInjected = false
        Log.d(TAG, "three-finger start: trackingBase=$trackingBase")
    }

    override fun onGestureMove(settings: TouchpadSettings, nowMs: Long, rawDispDx: Int, rawDispDy: Int) {
        if (!settings.threeFingerMove) return

        maybeLockDirection(rawDispDx, rawDispDy)

        when (direction) {
            ThreeDir.UP, ThreeDir.LEFT, ThreeDir.RIGHT -> updateNavBarGesture(settings, rawDispDx, rawDispDy)
            ThreeDir.DOWN -> updateDownSwipeLegacy(settings, rawDispDx, rawDispDy)
            null -> Unit
        }
    }

    override fun onGestureEnd(settings: TouchpadSettings) {
        if (navInjected) {
            NativeBridge.releaseAllTouches(touchFd, 1)
        }
        if (downModeInjected) {
            NativeBridge.releaseAllTouches(touchFd, 3)
        }
        Log.d(TAG, "three-finger end: consumed=$consumed navInjected=$navInjected downModeInjected=$downModeInjected")
    }

    override fun consumedAsDirectionalAction(): Boolean = consumed

    private fun detectDirection(rawDispDx: Int, rawDispDy: Int): ThreeDir? {
        val adx = abs(rawDispDx)
        val ady = abs(rawDispDy)
        if (adx < DIRECTION_LOCK_THRESHOLD_PX && ady < DIRECTION_LOCK_THRESHOLD_PX) return null

        return if (ady >= adx) {
            if (rawDispDy < 0) ThreeDir.UP else ThreeDir.DOWN
        } else {
            if (rawDispDx < 0) ThreeDir.LEFT else ThreeDir.RIGHT
        }
    }

    private fun maybeLockDirection(rawDispDx: Int, rawDispDy: Int) {
        if (direction != null) return
        direction = detectDirection(rawDispDx, rawDispDy)
        if (direction != null) {
            Log.d(TAG, "direction locked: direction=$direction rawDispDx=$rawDispDx rawDispDy=$rawDispDy")
        }
    }

    private fun updateNavBarGesture(settings: TouchpadSettings, rawDispDx: Int, rawDispDy: Int) {
        if (!navInjected) {
            navStartDispX = screenWidth / 2
            navStartDispY = NAV_START_Y_PX
            val start = toUinput(settings, navStartDispX, navStartDispY)
            navUiX = start.first
            navUiY = start.second
            injectSingleTouch(navUiX, navUiY)
            navInjected = true
            consumed = true
            Log.d(TAG, "nav mode injected: direction=$direction startX=$navUiX startY=$navUiY")
        }

        val transformed = transformDisp(settings, rawDispDx, rawDispDy)
        val dispDx = transformed.first
        val dispDy = transformed.second

        val targetDispX: Int
        val targetDispY: Int
        when (direction) {
            ThreeDir.UP -> {
                targetDispX = (navStartDispX + dispDx).coerceIn(0, screenWidth - 1)
                targetDispY = (navStartDispY + dispDy).coerceIn(0, screenHeight - 1)
            }
            ThreeDir.LEFT, ThreeDir.RIGHT -> {
                val maxHorizontalStep = (screenWidth / HORIZONTAL_STEP_DIVISOR).coerceAtLeast(1)
                val clampedDispDx = dispDx.coerceIn(-maxHorizontalStep, maxHorizontalStep)
                targetDispX = (navStartDispX + clampedDispDx).coerceIn(0, screenWidth - 1)
                targetDispY = navStartDispY
            }
            else -> return
        }

        val target = toUinput(settings, targetDispX, targetDispY)
        navUiX = target.first
        navUiY = target.second

        injectSingleTouch(navUiX, navUiY)
    }

    private fun updateDownSwipeLegacy(settings: TouchpadSettings, rawDispDx: Int, rawDispDy: Int) {
        val uinputW = if (settings.swapAxes) screenHeight else screenWidth
        val uinputH = if (settings.swapAxes) screenWidth else screenHeight

        if (!downModeInjected) {
            val pts = IntArray(4 * 3)
            for (i in 0..2) {
                pts[i * 4 + 0] = i
                pts[i * 4 + 1] = uinputW / 2 + (i - 1) * 100
                pts[i * 4 + 2] = uinputH / 2
                pts[i * 4 + 3] = trackingBase + i
            }
            NativeBridge.injectTouch(touchFd, pts, 3)
            downModeInjected = true
            consumed = true
            Log.d(TAG, "down mode injected")
        }

        val transformed = transformDisp(settings, rawDispDx, rawDispDy)
        val dispDx = transformed.first
        val dispDy = transformed.second

        val uiDx = if (!settings.swapAxes) dispDx else dispDy
        val uiDy = if (!settings.swapAxes) dispDy else dispDx

        val pts = IntArray(4 * 3)
        for (i in 0..2) {
            val finalX = (uinputW / 2 + (i - 1) * 100 + uiDx).coerceIn(0, uinputW - 1)
            val finalY = (uinputH / 2 + uiDy).coerceIn(0, uinputH - 1)
            pts[i * 4 + 0] = i
            pts[i * 4 + 1] = finalX
            pts[i * 4 + 2] = finalY
            pts[i * 4 + 3] = trackingBase + i
        }
        NativeBridge.injectTouch(touchFd, pts, 3)
    }

    private fun transformDisp(settings: TouchpadSettings, rawDispDx: Int, rawDispDy: Int): Pair<Int, Int> {
        var dispDx = (rawDispDx * settings.touchInjectSpeed).toInt()
        var dispDy = (rawDispDy * settings.touchInjectSpeed).toInt()
        if (settings.invertX) dispDx = -dispDx
        if (settings.invertY) dispDy = -dispDy
        return Pair(dispDx, dispDy)
    }

    private fun injectSingleTouch(uiX: Int, uiY: Int) {
        val pts = intArrayOf(0, uiX, uiY, trackingBase)
        NativeBridge.injectTouch(touchFd, pts, 1)
    }

    private fun toUinput(settings: TouchpadSettings, dispX: Int, dispY: Int): Pair<Int, Int> {
        val uinputW = if (settings.swapAxes) screenHeight else screenWidth
        val uinputH = if (settings.swapAxes) screenWidth else screenHeight
        val uiX = if (!settings.swapAxes) dispX else dispY.coerceIn(0, uinputW - 1)
        val uiY = if (!settings.swapAxes) dispY.coerceIn(0, uinputH - 1) else dispX.coerceIn(0, uinputH - 1)
        return Pair(uiX, uiY)
    }
}
