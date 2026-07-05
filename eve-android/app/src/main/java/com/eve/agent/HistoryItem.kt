package com.eve.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single completed (or failed) task entry shown in the History tab.
 */
data class HistoryItem(
    val taskId: String,
    val action: String,
    val result: String,
    val failed: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
}
