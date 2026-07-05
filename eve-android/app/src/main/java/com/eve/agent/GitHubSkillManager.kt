package com.eve.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

/**
 * GitHubSkillManager fetches skill repositories from a GitHub user account.
 *
 * Skills are identified as repos whose names start with "eve-skill-" by convention.
 * Each skill repo is expected to contain a Python package that EVE can load at runtime.
 *
 * TODO: Download and cache skill packages; wire into SkillSandboxService.
 */
class GitHubSkillManager(private val context: Context) {

    private val client = OkHttpClient()

    /**
     * List all repositories for [user] that follow the eve-skill-* naming convention.
     * Requires a valid GitHub personal access [token] with `repo` or `public_repo` scope.
     *
     * @throws IOException on network failure.
     */
    fun listSkills(user: String, token: String): List<SkillInfo> {
        val request = Request.Builder()
            .url("https://api.github.com/users/$user/repos?per_page=100")
            .addHeader("Authorization", "token $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub API error: ${response.code}")
            response.body?.string() ?: "[]"
        }

        val array = JSONArray(body)
        val skills = mutableListOf<SkillInfo>()
        for (i in 0 until array.length()) {
            val repo = array.getJSONObject(i)
            val name = repo.getString("name")
            if (name.startsWith("eve-skill-")) {
                skills += SkillInfo(
                    name = name,
                    description = repo.optString("description"),
                    htmlUrl = repo.getString("html_url"),
                    cloneUrl = repo.getString("clone_url")
                )
            }
        }
        return skills
    }

    data class SkillInfo(
        val name: String,
        val description: String,
        val htmlUrl: String,
        val cloneUrl: String
    )
}
