package com.fasa70.bettertouchpad.system

import android.util.Log
import com.fasa70.bettertouchpad.NativeBridge
import com.fasa70.bettertouchpad.TouchpadSettings

private const val TAG = "LegacyThreeFingerAdapter"

class LegacyThreeFingerAdapter(
    private val touchFd: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
) : ThreeFingerActionAdapter {

    private var trackingBase = 0

    override fun onGestureStart(settings: TouchpadSettings, trackingBase: Int) {
        this.trackingBase = trackingBase
        if (!settings.threeFingerMove) return

        val uinputW = if (settings.swapAxes) screenHeight else screenWidth
        val uinputH = if (settings.swapAxes) screenWidth else screenHeight

        val pts = IntArray(4 * 3)
        for (i in 0..2) {
            pts[i * 4 + 0] = i
            pts[i * 4 + 1] = uinputW / 2 + (i - 1) * 100
            pts[i * 4 + 2] = uinputH / 2
            pts[i * 4 + 3] = this.trackingBase + i
        }
        NativeBridge.injectTouch(touchFd, pts, 3)
        Log.d(TAG, "legacy three-finger start")
    }

    override fun onGestureMove(settings: TouchpadSettings, nowMs: Long, rawDispDx: Int, rawDispDy: Int) {
        if (!settings.threeFingerMove) return

        val uinputW = if (settings.swapAxes) screenHeight else screenWidth
        val uinputH = if (settings.swapAxes) screenWidth else screenHeight

        val sp = settings.touchInjectSpeed
        var dispDx = (rawDispDx.toFloat() * sp).toInt()
        var dispDy = (rawDispDy.toFloat() * sp).toInt()

        if (settings.invertX) dispDx = -dispDx
        if (settings.invertY) dispDy = -dispDy

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

    override fun onGestureEnd(settings: TouchpadSettings) {
        NativeBridge.releaseAllTouches(touchFd, 3)
        Log.d(TAG, "legacy three-finger end")
    }

    override fun consumedAsDirectionalAction(): Boolean = false
}
