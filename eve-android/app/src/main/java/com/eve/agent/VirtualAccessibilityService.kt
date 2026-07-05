package com.eve.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VirtualAccessibilityService is the bridge between Android's Accessibility
 * framework and VirtualComputer.
 *
 * Key points:
 *  - The system binds this service automatically once the user grants permission.
 *  - On connection we register ourselves with the VirtualComputer singleton.
 *  - takeScreenshot() (API 30+) is wrapped as requestScreenshot() to avoid
 *    naming collision with the framework method and to support the blocking
 *    call pattern expected by the Python bridge.
 */
class VirtualAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Register with the computer singleton so it can dispatch gestures and
        // request screenshots through us.
        // Guard against the edge case where the service connects before
        // EveService has had a chance to call VirtualComputer.init().
        try {
            VirtualComputer.getInstance().setAccessibilityService(this)
        } catch (_: IllegalStateException) {
            // EveService.init() hasn't run yet (e.g. system rebound the
            // service after a crash).  EveService.onCreate() will call
            // init() and the computer will pick up the reference once the
            // accessibility service is set later via setAccessibilityService().
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Future: forward relevant events to the orchestrator's event bus.
    }

    override fun onInterrupt() {
        // Called when the system interrupts accessibility feedback.
    }

    /**
     * Capture the current screen as a [Bitmap].
     *
     * Uses the framework's [AccessibilityService.takeScreenshot] (API 30+).
     * Returns null on older API levels or if the capture times out.
     */
    fun requestScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null   // API 30+

        var result: Bitmap? = null
        val latch = CountDownLatch(1)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    result = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )

        latch.await(5, TimeUnit.SECONDS)
        return result
    }
}
