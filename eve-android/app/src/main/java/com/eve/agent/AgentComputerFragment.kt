package com.eve.agent

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AgentComputerFragment — live mirror of the device screen.
 *
 * Features:
 *  - Polls [VirtualComputer.captureScreen] every 3 s automatically.
 *  - Tap on the screenshot → translates ImageView coordinates to real screen
 *    coordinates and dispatches a click gesture via [VirtualComputer.click].
 *  - Command bar at the bottom: type an action name (e.g. "screenshot",
 *    "http_get") and it is sent to the Hermes gateway, which routes it to the
 *    correct agent.
 *  - Single "📷" button for an immediate one-shot capture.
 */
class AgentComputerFragment : Fragment() {

    private lateinit var screenshotView: ImageView
    private lateinit var placeholder: TextView
    private lateinit var refreshBadge: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var screenshotBtn: Button
    private lateinit var tapHint: TextView

    // Screen dimensions for coordinate mapping
    private var realScreenW = 0
    private var realScreenH = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_agent_computer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        screenshotView = view.findViewById(R.id.screenshotView)
        placeholder     = view.findViewById(R.id.screenshotPlaceholder)
        refreshBadge    = view.findViewById(R.id.refreshBadge)
        commandInput    = view.findViewById(R.id.commandInput)
        sendBtn         = view.findViewById(R.id.sendCommandBtn)
        screenshotBtn   = view.findViewById(R.id.screenshotBtn)
        tapHint         = view.findViewById(R.id.tapHint)

        setupScreenshotTap()
        setupCommandBar()
        startAutoRefresh()
    }

    // ── Screenshot display ────────────────────────────────────────────────────

    private fun startAutoRefresh() {
        refreshBadge.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshScreenshot()
                delay(3_000)
            }
        }
    }

    private suspend fun refreshScreenshot() {
        val bmp = withContext(Dispatchers.IO) {
            try { VirtualComputer.getInstance().captureScreen() } catch (_: Exception) { null }
        }
        if (bmp != null) {
            realScreenW = bmp.width
            realScreenH = bmp.height
            screenshotView.setImageBitmap(bmp)
            placeholder.visibility = View.GONE
            refreshBadge.visibility = View.VISIBLE
        } else {
            placeholder.visibility = View.VISIBLE
            refreshBadge.visibility = View.GONE
        }
    }

    // ── Tap-to-click ─────────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun setupScreenshotTap() {
        screenshotBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { refreshScreenshot() }
        }

        screenshotView.setOnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
            if (realScreenW == 0 || realScreenH == 0) return@setOnTouchListener true

            // ImageView uses fitCenter — calculate the actual image rect inside the view
            val viewW   = v.width.toFloat()
            val viewH   = v.height.toFloat()
            val imgW    = realScreenW.toFloat()
            val imgH    = realScreenH.toFloat()
            val scale   = minOf(viewW / imgW, viewH / imgH)
            val offsetX = (viewW - imgW * scale) / 2f
            val offsetY = (viewH - imgH * scale) / 2f

            val relX = ((event.x - offsetX) / scale).toInt().coerceIn(0, realScreenW)
            val relY = ((event.y - offsetY) / scale).toInt().coerceIn(0, realScreenH)

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    VirtualComputer.getInstance().click(relX, relY)
                    EveEventBus.emit(EveEvent.LogLine("Tap dispatched → ($relX, $relY)"))
                } catch (e: Exception) {
                    EveEventBus.emit(EveEvent.LogLine("Click failed: ${e.message}", "ERROR"))
                }
            }
            true
        }
    }

    // ── Command bar ───────────────────────────────────────────────────────────

    private fun setupCommandBar() {
        val submit = {
            val text = commandInput.text.toString().trim()
            if (text.isNotBlank()) {
                submitCommand(text)
                commandInput.text.clear()
            }
        }
        sendBtn.setOnClickListener { submit() }
        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit(); true
            } else false
        }
    }

    /**
     * Parse [input] as either a bare action name ("screenshot") or
     * "action param=value param2=value2" and submit it to EveService.
     */
    private fun submitCommand(input: String) {
        val parts  = input.trim().split("\\s+".toRegex())
        val action = parts[0]
        val params = parts.drop(1)
            .mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx > 0) kv.substring(0, idx) to kv.substring(idx + 1) else null
            }
            .toMap()

        val service = (activity as? MainActivity)?.eveService
        if (service == null) {
            Toast.makeText(requireContext(), "EVE service not connected", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Sending: $action", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            service.submitTask(action, params)
        }
    }
}
