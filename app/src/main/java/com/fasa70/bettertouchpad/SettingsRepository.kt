package com.fasa70.bettertouchpad

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TouchpadSettings(
    // Feature toggles
    val singleFingerMove: Boolean = true,
    val singleFingerTap: Boolean = true,
    val physicalClick: Boolean = true,
    val doubleTapDrag: Boolean = true,
    val twoFingerTap: Boolean = true,
    val twoFingerScroll: Boolean = true,
    val edgeSwipe: Boolean = true,
    val threeFingerMove: Boolean = true,
    val naturalScroll: Boolean = false,

    // Sensitivities
    val cursorSensitivity: Float = 1.5f,
    val scrollSensitivity: Float = 1.0f,
    val touchInjectSpeed: Float = 1.0f,

    // Touchpad coordinate range (customizable for device compatibility)
    val padMaxX: Int = 28790,
    val padMaxY: Int = 17990,

    // Edge swipe threshold (fraction of padMaxX, 0.0~1.0)
    val edgeThreshold: Float = 0.08f,

    // Axis correction for injected touch (2-finger & 3-finger gestures)
    val swapAxes: Boolean = false,
    val invertX: Boolean = false,
    val invertY: Boolean = false
)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("touchpad_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TouchpadSettings> = _settings.asStateFlow()

    fun get(): TouchpadSettings = _settings.value

    fun update(block: TouchpadSettings.() -> TouchpadSettings) {
        val new = block(_settings.value)
        _settings.value = new
        save(new)
    }

    private fun load() = TouchpadSettings(
        singleFingerMove    = prefs.getBoolean("singleFingerMove", true),
        singleFingerTap     = prefs.getBoolean("singleFingerTap", true),
        physicalClick       = prefs.getBoolean("physicalClick", true),
        doubleTapDrag       = prefs.getBoolean("doubleTapDrag", true),
        twoFingerTap        = prefs.getBoolean("twoFingerTap", true),
        twoFingerScroll     = prefs.getBoolean("twoFingerScroll", true),
        edgeSwipe           = prefs.getBoolean("edgeSwipe", true),
        threeFingerMove     = prefs.getBoolean("threeFingerMove", true),
        naturalScroll       = prefs.getBoolean("naturalScroll", false),
        cursorSensitivity   = prefs.getFloat("cursorSensitivity", 1.5f),
        scrollSensitivity   = prefs.getFloat("scrollSensitivity", 1.0f),
        touchInjectSpeed    = prefs.getFloat("touchInjectSpeed", 1.0f),
        padMaxX             = prefs.getInt("padMaxX", 28790),
        padMaxY             = prefs.getInt("padMaxY", 17990),
        edgeThreshold       = prefs.getFloat("edgeThreshold", 0.08f),
        swapAxes            = prefs.getBoolean("swapAxes", false),
        invertX             = prefs.getBoolean("invertX", false),
        invertY             = prefs.getBoolean("invertY", false)
    )

    private fun save(s: TouchpadSettings) {
        prefs.edit().apply {
            putBoolean("singleFingerMove", s.singleFingerMove)
            putBoolean("singleFingerTap", s.singleFingerTap)
            putBoolean("physicalClick", s.physicalClick)
            putBoolean("doubleTapDrag", s.doubleTapDrag)
            putBoolean("twoFingerTap", s.twoFingerTap)
            putBoolean("twoFingerScroll", s.twoFingerScroll)
            putBoolean("edgeSwipe", s.edgeSwipe)
            putBoolean("threeFingerMove", s.threeFingerMove)
            putBoolean("naturalScroll", s.naturalScroll)
            putFloat("cursorSensitivity", s.cursorSensitivity)
            putFloat("scrollSensitivity", s.scrollSensitivity)
            putFloat("touchInjectSpeed", s.touchInjectSpeed)
            putInt("padMaxX", s.padMaxX)
            putInt("padMaxY", s.padMaxY)
            putFloat("edgeThreshold", s.edgeThreshold)
            putBoolean("swapAxes", s.swapAxes)
            putBoolean("invertX", s.invertX)
            putBoolean("invertY", s.invertY)
        }.apply()
    }
}

