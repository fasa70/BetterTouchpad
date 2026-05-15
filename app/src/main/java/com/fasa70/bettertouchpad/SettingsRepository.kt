package com.fasa70.bettertouchpad

import android.content.Context
import android.content.SharedPreferences
import com.fasa70.bettertouchpad.system.ThreeFingerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TouchpadSettings(
    // Onboarding
    val hasSeenOnboarding: Boolean = false,

    // Auto-detect touchpad device path and coordinate range
    val autoDetectDevice: Boolean = true,
    val devicePath: String = "/dev/input/event5",

    // Feature toggles
    val singleFingerMove: Boolean = true,
    val singleFingerTap: Boolean = true,
    val physicalClick: Boolean = true,
    val doubleTapDrag: Boolean = true,
    val twoFingerTap: Boolean = true,
    val twoFingerScroll: Boolean = true,
    val twoFingerZoom: Boolean = true,
    val zoomSensitivity: Float = 1.5f,
    val minPinchDistance: Float = 50f,
    val edgeSwipe: Boolean = true,
    val threeFingerMove: Boolean = true,
    val threeFingerMode: ThreeFingerMode = ThreeFingerMode.LEGACY,
    val threeFingerMiddleClick: Boolean = true,
    val naturalScroll: Boolean = true,

    // Sensitivities
    val cursorSensitivity: Float = 0.7f,
    val scrollSensitivity: Float = 0.5f,
    val touchInjectSpeed: Float = 1.0f,

    // Touchpad coordinate range (customizable for device compatibility)
    val padMaxX: Int = 2879,
    val padMaxY: Int = 1799,

    // Edge swipe threshold (fraction of padMaxX, 0.0~1.0)
    val edgeThreshold: Float = 0.1f,

    // Axis correction for injected touch (2-finger & 3-finger gestures)
    val swapAxes: Boolean = true,
    val invertX: Boolean = false,
    val invertY: Boolean = true,

    // Double-tap drag interval (ms): max time between first tap-up and second tap-down
    val doubleTapIntervalMs: Int = 100,

    // Exclusively grab the input device (EVIOCGRAB); disable on devices where it causes issues
    val exclusiveGrab: Boolean = true,

    // Two-finger top swipe (notification center / control center)
    val twoFingerTopSwipe: Boolean = true,
    val topEdgeZoneRatio: Float = 0.15f,
    val topEdgeThreshold: Float = 0.05f,

    // Two-finger bottom swipe (mouse back/forward)
    val twoFingerBottomSwipe: Boolean = true,
    val bottomEdgeZoneRatio: Float = 0.15f,
    val bottomEdgeThreshold: Float = 0.05f
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
        hasSeenOnboarding   = prefs.getBoolean("hasSeenOnboarding", false),
        autoDetectDevice    = prefs.getBoolean("autoDetectDevice", true),
        devicePath          = prefs.getString("devicePath", "/dev/input/event5") ?: "/dev/input/event5",
        singleFingerMove    = prefs.getBoolean("singleFingerMove", true),
        singleFingerTap     = prefs.getBoolean("singleFingerTap", true),
        physicalClick       = prefs.getBoolean("physicalClick", true),
        doubleTapDrag       = prefs.getBoolean("doubleTapDrag", true),
        twoFingerTap        = prefs.getBoolean("twoFingerTap", true),
        twoFingerScroll     = prefs.getBoolean("twoFingerScroll", true),
        twoFingerZoom       = prefs.getBoolean("twoFingerZoom", true),
        zoomSensitivity     = prefs.getFloat("zoomSensitivity", 1.5f),
        minPinchDistance    = prefs.getFloat("minPinchDistance", 50f),
        edgeSwipe           = prefs.getBoolean("edgeSwipe", true),
        threeFingerMove     = prefs.getBoolean("threeFingerMove", true),
        threeFingerMode     = prefs.getString("threeFingerMode", "LEGACY")
            ?.let { runCatching { ThreeFingerMode.valueOf(it) }.getOrNull() }
            ?: ThreeFingerMode.LEGACY,
        threeFingerMiddleClick = prefs.getBoolean("threeFingerMiddleClick", true),
        naturalScroll       = prefs.getBoolean("naturalScroll", true),
        cursorSensitivity   = prefs.getFloat("cursorSensitivity", 0.7f),
        scrollSensitivity   = prefs.getFloat("scrollSensitivity", 0.5f),
        touchInjectSpeed    = prefs.getFloat("touchInjectSpeed", 1.0f),
        padMaxX             = prefs.getInt("padMaxX", 2879),
        padMaxY             = prefs.getInt("padMaxY", 1799),
        edgeThreshold       = prefs.getFloat("edgeThreshold", 0.1f),
        swapAxes            = prefs.getBoolean("swapAxes", true),
        invertX             = prefs.getBoolean("invertX", false),
        invertY             = prefs.getBoolean("invertY", true),
        doubleTapIntervalMs = prefs.getInt("doubleTapIntervalMs", 100),
        exclusiveGrab       = prefs.getBoolean("exclusiveGrab", true),
        twoFingerTopSwipe   = prefs.getBoolean("twoFingerTopSwipe", true),
        topEdgeZoneRatio    = prefs.getFloat("topEdgeZoneRatio", 0.25f),
        topEdgeThreshold    = prefs.getFloat("topEdgeThreshold", 0.1f),
        twoFingerBottomSwipe = prefs.getBoolean("twoFingerBottomSwipe", true),
        bottomEdgeZoneRatio = prefs.getFloat("bottomEdgeZoneRatio", 0.25f),
        bottomEdgeThreshold = prefs.getFloat("bottomEdgeThreshold", 0.1f)
    )

    private fun save(s: TouchpadSettings) {
        prefs.edit().apply {
            putBoolean("hasSeenOnboarding", s.hasSeenOnboarding)
            putBoolean("autoDetectDevice", s.autoDetectDevice)
            putString("devicePath", s.devicePath)
            putBoolean("singleFingerMove", s.singleFingerMove)
            putBoolean("singleFingerTap", s.singleFingerTap)
            putBoolean("physicalClick", s.physicalClick)
            putBoolean("doubleTapDrag", s.doubleTapDrag)
            putBoolean("twoFingerTap", s.twoFingerTap)
            putBoolean("twoFingerScroll", s.twoFingerScroll)
            putBoolean("twoFingerZoom", s.twoFingerZoom)
            putFloat("zoomSensitivity", s.zoomSensitivity)
            putFloat("minPinchDistance", s.minPinchDistance)
            putBoolean("edgeSwipe", s.edgeSwipe)
            putBoolean("threeFingerMove", s.threeFingerMove)
            putString("threeFingerMode", s.threeFingerMode.name)
            putBoolean("threeFingerMiddleClick", s.threeFingerMiddleClick)
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
            putInt("doubleTapIntervalMs", s.doubleTapIntervalMs)
            putBoolean("exclusiveGrab", s.exclusiveGrab)
            putBoolean("twoFingerTopSwipe", s.twoFingerTopSwipe)
            putFloat("topEdgeZoneRatio", s.topEdgeZoneRatio)
            putFloat("topEdgeThreshold", s.topEdgeThreshold)
            putBoolean("twoFingerBottomSwipe", s.twoFingerBottomSwipe)
            putFloat("bottomEdgeZoneRatio", s.bottomEdgeZoneRatio)
            putFloat("bottomEdgeThreshold", s.bottomEdgeThreshold)
        }.apply()
    }
}

