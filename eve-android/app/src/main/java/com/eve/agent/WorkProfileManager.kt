package com.eve.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * WorkProfileManager handles Device-Owner / Profile-Owner capabilities such as
 * enabling a managed (work) profile for sandboxed agent operations.
 *
 * IMPORTANT: these APIs require the app to be a Device Owner or Profile Owner,
 * provisioned via NFC / QR-code / ADB during device setup.  Calling them
 * without the required privilege will throw a SecurityException.
 */
class WorkProfileManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent = ComponentName(context, EveDeviceAdminReceiver::class.java)

    /**
     * Returns true if this app is currently an active device administrator.
     */
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /**
     * Enables the managed profile once the app has been set as Profile Owner.
     * Returns true on success.
     *
     * NOTE: [DevicePolicyManager.setProfileEnabled] is a Profile-Owner-only API
     * (not Device-Owner).  The component name passed must be the profile owner
     * component that was declared during provisioning.
     */
    fun enableProfile(): Boolean {
        return try {
            dpm.setProfileEnabled(adminComponent)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Lock the device immediately (requires FORCE_LOCK policy or Device Owner).
     */
    fun lockNow(): Boolean {
        return try {
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
