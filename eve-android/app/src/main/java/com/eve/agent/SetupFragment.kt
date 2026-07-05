package com.eve.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SetupFragment — permission checklist and API token display.
 *
 * Each permission row shows a live ✔/✘ and is tappable to open the
 * relevant system settings screen.  The Hermes token is read from
 * internal storage and displayed with a one-tap copy button so the
 * user can start sending commands without an adb shell.
 */
class SetupFragment : Fragment() {

    private lateinit var iconAccessibility: TextView
    private lateinit var iconDeviceAdmin: TextView
    private lateinit var iconNotification: TextView
    private lateinit var tokenText: TextView
    private lateinit var copyBtn: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iconAccessibility = view.findViewById(R.id.iconAccessibility)
        iconDeviceAdmin   = view.findViewById(R.id.iconDeviceAdmin)
        iconNotification  = view.findViewById(R.id.iconNotification)
        tokenText         = view.findViewById(R.id.tokenText)
        copyBtn           = view.findViewById(R.id.copyTokenBtn)

        // Row click targets
        view.findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.findViewById<View>(R.id.rowDeviceAdmin).setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(requireContext(), EveDeviceAdminReceiver::class.java)
                )
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required for lock-screen and work-profile control")
            }
            startActivity(intent)
        }
        view.findViewById<View>(R.id.rowNotification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }

        copyBtn.setOnClickListener {
            val token = tokenText.text.toString()
            if (token.length > 8) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Hermes Token", token))
                Toast.makeText(requireContext(), "Token copied", Toast.LENGTH_SHORT).show()
            }
        }

        loadToken()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionIcons()
    }

    // ── Permission checks ────────────────────────────────────────────────────

    private fun refreshPermissionIcons() {
        iconAccessibility.text = if (isAccessibilityEnabled()) "✔" else "✘"
        iconAccessibility.setTextColor(
            ContextCompat.getColor(requireContext(),
                if (isAccessibilityEnabled()) R.color.status_ok else R.color.status_error)
        )

        val adminActive = isDeviceAdminActive()
        iconDeviceAdmin.text = if (adminActive) "✔" else "–"
        iconDeviceAdmin.setTextColor(
            ContextCompat.getColor(requireContext(),
                if (adminActive) R.color.status_ok else R.color.status_warn)
        )

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        iconNotification.text = if (notifGranted) "✔" else "✘"
        iconNotification.setTextColor(
            ContextCompat.getColor(requireContext(),
                if (notifGranted) R.color.status_ok else R.color.status_warn)
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val pref = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return pref.contains("${requireContext().packageName}/.VirtualAccessibilityService")
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager
        val admin = ComponentName(requireContext(), EveDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    // ── Token display ────────────────────────────────────────────────────────

    private fun loadToken() {
        viewLifecycleOwner.lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                try {
                    File(requireContext().filesDir, "hermes_token.txt")
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                } catch (_: Exception) { null }
            }
            if (token != null) {
                tokenText.text = token
            } else {
                tokenText.text = "Not generated yet — start EVE service first"
            }
        }
    }
}
