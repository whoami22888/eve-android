package com.eve.agent

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
        // Store ourselves so VirtualComputer.init() can pick us up even when
        // init() runs *after* this callback fires (the common race on boot).
        pendingInstance = this
        // Also register immediately if the singleton is already initialised.
        try {
            VirtualComputer.getInstance().setAccessibilityService(this)
        } catch (_: IllegalStateException) {
            // Singleton not ready yet — VirtualComputer.init() will consume
            // `pendingInstance` and call setAccessibilityService() itself.
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (pendingInstance === this) pendingInstance = null
        return super.onUnbind(intent)
    }

    companion object {
        /**
         * Holds a reference to the most recently connected instance.
         * Consumed once by [VirtualComputer.init] and cleared to avoid leaks.
         *
         * Marked @Volatile so reads/writes across the Kotlin and Chaquopy
         * threads are immediately visible.
         */
        @Volatile
        var pendingInstance: VirtualAccessibilityService? = null
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
    @SuppressLint("NewApi")
    fun requestScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null   // API 30+

        var result: Bitmap? = null
        val latch = CountDownLatch(1)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // wrapHardwareBuffer gives a hardware-backed bitmap.
                    // Hardware bitmaps cannot be read by OpenCV or Pillow on
                    // the Python side, so we must copy to a software bitmap
                    // before releasing the HardwareBuffer.
                    val hw = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
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
