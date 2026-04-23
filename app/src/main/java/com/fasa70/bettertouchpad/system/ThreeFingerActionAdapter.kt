package com.fasa70.bettertouchpad.system

import com.fasa70.bettertouchpad.TouchpadSettings

interface ThreeFingerActionAdapter {
    fun onGestureStart(settings: TouchpadSettings, trackingBase: Int)
    fun onGestureMove(settings: TouchpadSettings, nowMs: Long, rawDispDx: Int, rawDispDy: Int)
    fun onGestureEnd(settings: TouchpadSettings)
    fun consumedAsDirectionalAction(): Boolean
}
