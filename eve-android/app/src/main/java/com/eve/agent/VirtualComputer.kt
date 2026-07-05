package com.eve.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.chaquo.python.Python
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * VirtualComputer wraps all device-control primitives that the Python agents
 * invoke via Chaquopy's `jclass` bridge.
 *
 * Threading: all public methods are safe to call from a background thread.
 *
 * Singleton pattern is required because Python's `android_computer.py` obtains
 * the instance via `VirtualComputer.getInstance()`.
 */
class VirtualComputer private constructor(private val context: Context) {

    private var accessibilityService: VirtualAccessibilityService? = null

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // -------------------------------------------------------------------------
    // Accessibility bridge registration
    // -------------------------------------------------------------------------

    fun setAccessibilityService(service: VirtualAccessibilityService) {
        this.accessibilityService = service
    }

    // -------------------------------------------------------------------------
    // Screen capture
    // -------------------------------------------------------------------------

    /** Returns a screenshot bitmap, or null if the accessibility service is not bound. */
    fun captureScreen(): Bitmap? = accessibilityService?.requestScreenshot()

    // -------------------------------------------------------------------------
    // Input simulation
    // -------------------------------------------------------------------------

    fun moveMouse(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        accessibilityService?.dispatchGesture(gesture, null, null)
    }

    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        accessibilityService?.dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String) {
        val focused = accessibilityService
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // -------------------------------------------------------------------------
    // Script execution
    // -------------------------------------------------------------------------

    /**
     * Execute a script in the given language and return its stdout as a String.
     * Supported languages: "python", "shell" / "sh" / "bash".
     */
    fun executeScript(language: String, script: String, args: List<String> = emptyList()): String {
        return when (language.lowercase()) {
            "python"          -> executePython(script)
            "shell", "sh", "bash" -> executeShell(script)
            else              -> "Unsupported language: $language"
        }
    }

    private fun executePython(script: String): String {
        return try {
            val py = Python.getInstance()
            val io = py.getModule("io")
            val sio = io.callAttr("StringIO")
            val sys = py.getModule("sys")
            val originalStdout = sys["stdout"]
            sys["stdout"] = sio
            try {
                py.getBuiltins().callAttr("exec", script)
            } finally {
                sys["stdout"] = originalStdout   // always restore, even on exception
            }
            sio.callAttr("getvalue").toString()
        } catch (e: Exception) {
            "Python error: ${e.message}"
        }
    }

    private fun executeShell(script: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            // 30-second hard timeout — prevents runaway shell commands from
            // blocking the agent thread indefinitely.
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "Shell error: timed out after 30 s"
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) output else "Shell error (exit $exitCode): $output"
        } catch (e: Exception) {
            "Shell error: ${e.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Network helpers
    // -------------------------------------------------------------------------

    fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        return okHttp.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    companion object {
        @Volatile private var instance: VirtualComputer? = null

        /**
         * Initialise the singleton.  Must be called by EveService **before**
         * the Python runtime starts (android_computer.py calls getInstance()
         * at import time).
         *
         * Also consumes any [VirtualAccessibilityService] that connected while
         * the singleton was not yet initialised, closing the race window where
         * gestures/screenshots would silently fail.
         */
        fun init(context: Context): VirtualComputer =
            instance ?: synchronized(this) {
                instance ?: VirtualComputer(context.applicationContext).also { computer ->
                    instance = computer
                    // Consume any accessibility service that connected early
                    VirtualAccessibilityService.pendingInstance?.let { svc ->
                        computer.setAccessibilityService(svc)
                        VirtualAccessibilityService.pendingInstance = null
                    }
                }
            }

        /** Used by Python's android_computer.py via Chaquopy jclass bridge. */
        @JvmStatic
        fun getInstance(): VirtualComputer =
            instance ?: error("VirtualComputer not initialised — call init() first")
    }
}
