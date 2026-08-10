package com.eve.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent store for pipeline state; survives fragment/activity recreation and process restarts. */
class PipelineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eve_pipeline_state", Context.MODE_PRIVATE)
    private val key = "runs"

    data class Run(
        val taskId: String,
        val task: String,
        val project: String,
        val stage: String,
        val status: String,
        val progress: Int,
        val message: String,
        val output: String = "",
        val error: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    @Synchronized fun upsert(run: Run) {
        val current = load().filterNot { it.taskId == run.taskId }.toMutableList()
        current.add(0, run)
        val array = JSONArray()
        current.take(100).forEach { r ->
            array.put(JSONObject().apply {
                put("taskId", r.taskId); put("task", r.task); put("project", r.project)
                put("stage", r.stage); put("status", r.status); put("progress", r.progress)
                put("message", r.message); put("output", r.output); put("error", r.error)
                put("startedAt", r.startedAt); put("updatedAt", r.updatedAt)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    @Synchronized fun load(): List<Run> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(Run(
                        taskId = o.optString("taskId"), task = o.optString("task"), project = o.optString("project"),
                        stage = o.optString("stage"), status = o.optString("status"), progress = o.optInt("progress"),
                        message = o.optString("message"), output = o.optString("output"), error = o.optString("error"),
                        startedAt = o.optLong("startedAt"), updatedAt = o.optLong("updatedAt")
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear() = prefs.edit().remove(key).apply()
}
