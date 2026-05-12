package com.fasa70.bettertouchpad.system

import com.fasa70.bettertouchpad.TouchpadSettings

enum class ThreeFingerMode {
    /** 屏幕中央三点触摸 —— 适合 HyperOS（澎湃系统） */
    LEGACY,
    /** 底部手势条注入 —— 通用，适合非澎湃系统 */
    NAVBAR,
}

interface ThreeFingerActionAdapter {
    fun onGestureStart(settings: TouchpadSettings, trackingBase: Int)
    fun onGestureMove(settings: TouchpadSettings, nowMs: Long, rawDispDx: Int, rawDispDy: Int)
    fun onGestureEnd(settings: TouchpadSettings)
    fun consumedAsDirectionalAction(): Boolean
}
