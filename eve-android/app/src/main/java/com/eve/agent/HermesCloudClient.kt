package com.eve.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTPS-only EVE-to-Hermes relay. Never embed a provider API key in the APK. */
class HermesCloudClient(private val endpoint: String, private val token: String) {
    init {
        require(endpoint.startsWith("https://") && endpoint.length > 8)
        require(token.length >= 24)
    }

    private val base = endpoint.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String, postBody: String? = null): Request {
        val builder = Request.Builder().url("$base$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (postBody != null) builder.post(postBody.toRequestBody(jsonType))
        return builder.build()
    }

    private fun execute(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cloud HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty cloud response")
            try { JSONObject(body) } catch (_: Exception) {
                throw IOException("Invalid cloud JSON response")
            }
        }

    fun ready(): JSONObject = execute(request("/ready"))

    fun startRun(input: String): String {
        require(input.isNotBlank()) { "Task text is required" }
        val payload = JSONObject().put("input", input.trim())
        val id = execute(request("/api/runs", payload.toString())).getString("run_id")
        return validateRunId(id)
    }

    fun getRun(runId: String): JSONObject =
        execute(request("/api/runs/${validateRunId(runId)}"))

    fun stopRun(runId: String): JSONObject =
        execute(request("/api/runs/${validateRunId(runId)}/stop", "{}"))

    private fun validateRunId(id: String): String {
        require(Regex("^run_[0-9a-f]{32}$").matches(id)) { "Invalid run ID" }
        return id
    }
}
