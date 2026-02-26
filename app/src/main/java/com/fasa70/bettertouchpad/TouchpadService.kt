package com.fasa70.bettertouchpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TouchpadService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "touchpad_service"
private const val SOCKET_NAME = "bettertouchpad_helper"

class TouchpadService : Service() {

    companion object {
        var isRunning = false
            private set
    }

    private lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null

    private var evdevFd   = -1
    private var mouseFd   = -1
    private var touchFd   = -1
    private var serverFd  = -1

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent?.action == "STOP") {
            isRunning = false
            NativeBridge.stopEventLoop()
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        startTouchpadProcessing()
        return START_NOT_STICKY
    }

    private fun startTouchpadProcessing() {
        eventJob = scope.launch {
            try {
                val (w, h) = getScreenSize()
                Log.i(TAG, "Screen size: ${w}x${h}")

                // Deploy root_helper binary from native libs to filesDir
                val helperFile = deployHelper()
                Log.i(TAG, "Helper path: ${helperFile?.absolutePath}, exists=${helperFile?.exists()}")

                val evdevPath: String
                val detectedMaxX: Int
                val detectedMaxY: Int

                val s = settings.get()
                if (s.autoDetectDevice) {
                    Log.i(TAG, "Auto-detecting touchpad device via getevent -p ...")
                    val detected = detectTouchpadDevice()
                    if (detected != null) {
                        evdevPath = detected.first
                        detectedMaxX = detected.second
                        detectedMaxY = detected.third
                        // Persist detected coords if they differ from stored
                        if (detectedMaxX > 0 && detectedMaxY > 0 &&
                            (detectedMaxX != s.padMaxX || detectedMaxY != s.padMaxY || evdevPath != s.devicePath)) {
                            settings.update {
                                copy(devicePath = evdevPath, padMaxX = detectedMaxX, padMaxY = detectedMaxY)
                            }
                        }
                        Log.i(TAG, "Auto-detected: path=$evdevPath maxX=$detectedMaxX maxY=$detectedMaxY")
                    } else {
                        // Fallback to saved values
                        evdevPath = s.devicePath
                        detectedMaxX = s.padMaxX
                        detectedMaxY = s.padMaxY
                        Log.w(TAG, "Auto-detect failed, falling back to saved path: $evdevPath")
                    }
                } else {
                    evdevPath = s.devicePath
                    detectedMaxX = s.padMaxX
                    detectedMaxY = s.padMaxY
                }

                // Step 1: Try using root helper via SCM_RIGHTS fd passing
                // This bypasses SELinux because the fd is opened by root
                var fdObtainedViaHelper = false

                if (helperFile != null && helperFile.exists()) {
                    val sockFd = NativeBridge.createHelperSocket(SOCKET_NAME)
                    if (sockFd >= 0) {
                        serverFd = sockFd
                        // Launch root helper: it will open evdev+uinput as root and send fds
                        val helperCmd = "${helperFile.absolutePath} $SOCKET_NAME $evdevPath"
                        Log.i(TAG, "Launching helper: su -c $helperCmd")
                        val suProc = launchRootProcess(helperCmd)

                        // Wait for helper to connect and send fds (up to 5s)
                        val fds = NativeBridge.receiveFdsFromHelper(sockFd, 5000)
                        NativeBridge.closeDevice(sockFd)
                        serverFd = -1

                        suProc?.let {
                            val stdoutThread = Thread { it.inputStream.readBytes() }
                            val stderrThread = Thread { it.errorStream.readBytes() }
                            stdoutThread.start(); stderrThread.start()
                            it.waitFor()
                            stdoutThread.join(1000); stderrThread.join(1000)
                        }

                        if (fds != null && fds.size == 2 && fds[0] >= 0) {
                            evdevFd = fds[0]
                            // fds[1] is the uinput fd from helper — we don't use it directly
                            // because we need to setup uinput via our own ioctls
                            if (fds[1] >= 0 && fds[1] != fds[0]) NativeBridge.closeDevice(fds[1])
                            fdObtainedViaHelper = true
                            Log.i(TAG, "Got evdev fd=$evdevFd from root helper")
                        } else {
                            Log.w(TAG, "Helper fd passing failed, falling back to direct open")
                        }
                    }
                }

                // Step 2: Fallback — chmod and open directly
                if (!fdObtainedViaHelper) {
                    Log.i(TAG, "Trying direct open with chmod")
                    runShellAsRoot(
                        "chmod 666 $evdevPath; " +
                        "chmod 666 /dev/uinput; " +
                        "chcon u:object_r:input_device:s0 $evdevPath 2>/dev/null; " +
                        "setenforce 0 2>/dev/null; " +
                        "echo DONE"
                    )
                    kotlinx.coroutines.delay(400)

                    var retries = 5
                    while (evdevFd < 0 && retries-- > 0) {
                        evdevFd = NativeBridge.openDevice(evdevPath)
                        if (evdevFd < 0) {
                            Log.w(TAG, "Retry open $evdevPath (remaining=$retries)")
                            kotlinx.coroutines.delay(200)
                        }
                    }
                }

                if (evdevFd < 0) {
                    Log.e(TAG, "Failed to open $evdevPath — cannot start service")
                    isRunning = false
                    stopSelf()
                    return@launch
                }

                // Step 3: Grab device exclusively (best-effort)
                val grabbed = NativeBridge.grabDevice(evdevFd)
                Log.i(TAG, "Device grab result: $grabbed")

                // Step 4: Ensure /dev/uinput is accessible, create virtual mouse
                runShellAsRoot("chmod 666 /dev/uinput 2>/dev/null; echo OK")
                kotlinx.coroutines.delay(100)

                mouseFd = NativeBridge.createMouseDevice()
                if (mouseFd < 0) {
                    Log.e(TAG, "Failed to create virtual mouse device")
                    isRunning = false
                    cleanup()
                    stopSelf()
                    return@launch
                }

                // Step 5: Create virtual touch device
                // When swapAxes=true the touch panel's X axis spans the display height
                // and Y axis spans the display width, so pass dimensions accordingly.
                val swapAxes = settings.get().swapAxes
                val touchW = if (swapAxes) h else w
                val touchH = if (swapAxes) w else h
                touchFd = NativeBridge.createTouchDevice(touchW, touchH)
                if (touchFd < 0) {
                    Log.e(TAG, "Failed to create virtual touch device")
                    isRunning = false
                    cleanup()
                    stopSelf()
                    return@launch
                }

                // Step 6: Attach gesture recognizer callback
                val recognizer = GestureRecognizer(settings, mouseFd, touchFd, w, h)
                NativeBridge.setCallback(recognizer)

                // Step 7: Run blocking event loop
                Log.i(TAG, "Starting event loop on evdevFd=$evdevFd")
                NativeBridge.startEventLoop(evdevFd)
                Log.i(TAG, "Event loop ended normally")

            } catch (e: Exception) {
                Log.e(TAG, "Error in touchpad processing", e)
            } finally {
                isRunning = false
                cleanup()
            }
        }
    }

    /**
     * Copy root_helper from nativeLibraryDir to filesDir as an executable.
     * CMake builds it as libroot_helper.so so Android packages it in nativeLibraryDir.
     */
    private fun deployHelper(): File? {
        return try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            // CMake produces libroot_helper.so (actually an executable)
            val srcFile = File(nativeLibDir, "libroot_helper.so")
            val dstFile = File(filesDir, "root_helper")

            if (!srcFile.exists()) {
                Log.w(TAG, "libroot_helper.so not found at $srcFile")
                return null
            }
            if (!dstFile.exists() || dstFile.length() != srcFile.length()) {
                srcFile.copyTo(dstFile, overwrite = true)
            }
            dstFile.setExecutable(true, false)
            Log.i(TAG, "Helper deployed to $dstFile (${dstFile.length()} bytes)")
            dstFile
        } catch (e: Exception) {
            Log.e(TAG, "deployHelper failed: ${e.message}")
            null
        }
    }

    /**
     * Runs "getevent -p" as root and parses the output to find a touchpad device.
     * Looks for devices named "Xiaomi Touch" first; falls back to any device with
     * INPUT_PROP_POINTER that has ABS_X (0000) and ABS_Y (0001) axes.
     *
     * Returns Triple(path, maxX, maxY) or null on failure.
     */
    private fun detectTouchpadDevice(): Triple<String, Int, Int>? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -p"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            // Parse blocks separated by "add device N: /dev/input/eventX"
            val deviceRegex = Regex("""add device \d+: (/dev/input/\S+)""")
            val nameRegex   = Regex("""name:\s+"(.+)"""")
            val absAxisRegex = Regex("""(\w{4})\s+: value \d+, min \d+, max (\d+)""")
            val propPointerRegex = Regex("""INPUT_PROP_POINTER""")

            data class DeviceInfo(
                val path: String,
                val name: String,
                val absAxes: Map<String, Int>,   // code -> max
                val hasPropPointer: Boolean
            )

            val devices = mutableListOf<DeviceInfo>()
            val blocks = output.split(Regex("(?=add device \\d+:)")).filter { it.isNotBlank() }

            for (block in blocks) {
                val pathMatch = deviceRegex.find(block) ?: continue
                val path = pathMatch.groupValues[1]
                val name = nameRegex.find(block)?.groupValues?.get(1) ?: ""
                val axisMap = mutableMapOf<String, Int>()
                for (axisMatch in absAxisRegex.findAll(block)) {
                    axisMap[axisMatch.groupValues[1]] = axisMatch.groupValues[2].toIntOrNull() ?: 0
                }
                val hasProp = propPointerRegex.containsMatchIn(block)
                devices.add(DeviceInfo(path, name, axisMap, hasProp))
            }

            // Priority 1: named "Xiaomi Touch"
            var candidate = devices.firstOrNull { it.name == "Xiaomi Touch" }
            // Priority 2: any INPUT_PROP_POINTER device with ABS_X and ABS_Y
            if (candidate == null) {
                candidate = devices.firstOrNull { it.hasPropPointer && it.absAxes.containsKey("0000") && it.absAxes.containsKey("0001") }
            }
            // Priority 3: any device with ABS_MT_POSITION_X (0035) and ABS_MT_POSITION_Y (0036)
            if (candidate == null) {
                candidate = devices.firstOrNull { it.absAxes.containsKey("0035") && it.absAxes.containsKey("0036") }
            }

            candidate?.let { dev ->
                // Prefer MT axes (0035/0036), fall back to regular ABS_X/ABS_Y (0000/0001)
                val maxX = dev.absAxes["0035"] ?: dev.absAxes["0000"] ?: 0
                val maxY = dev.absAxes["0036"] ?: dev.absAxes["0001"] ?: 0
                if (maxX > 0 && maxY > 0) Triple(dev.path, maxX, maxY) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectTouchpadDevice failed: ${e.message}")
            null
        }
    }

    private fun launchRootProcess(cmd: String): Process? {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (e: Exception) {
            Log.e(TAG, "launchRootProcess failed: ${e.message}")
            null
        }
    }

    private fun cleanup() {
        NativeBridge.stopEventLoop()
        if (serverFd >= 0) { NativeBridge.closeDevice(serverFd); serverFd = -1 }
        if (evdevFd >= 0) {
            NativeBridge.ungrabDevice(evdevFd)
            NativeBridge.closeDevice(evdevFd)
            evdevFd = -1
        }
        if (touchFd >= 0) {
            NativeBridge.releaseAllTouches(touchFd, 3)
            NativeBridge.destroyTouchDevice(touchFd)
            touchFd = -1
        }
        if (mouseFd >= 0) {
            NativeBridge.destroyMouseDevice(mouseFd)
            mouseFd = -1
        }
    }

    override fun onDestroy() {
        isRunning = false
        NativeBridge.stopEventLoop()
        eventJob?.cancel()
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun runShellAsRoot(cmd: String): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdoutThread = Thread { p.inputStream.readBytes() }
            val stderrThread = Thread { p.errorStream.readBytes() }
            stdoutThread.start()
            stderrThread.start()
            val exitCode = p.waitFor()
            stdoutThread.join(2000)
            stderrThread.join(2000)
            Log.i(TAG, "runShellAsRoot exit=$exitCode cmd=$cmd")
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "su exec failed: ${e.message}")
            -1
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "触控板服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BetterTouchpad 后台运行通知"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TouchpadService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("触控板已启用")
            .setContentText("点击管理设置")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }
}

