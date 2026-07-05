package com.eve.agent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives device-admin lifecycle events (enabled, disabled, password changed, etc.)
 * Extend the relevant callbacks here as policy features are added.
 */
class EveDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin has been activated — store state or notify the service
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device admin has been removed
    }
}
