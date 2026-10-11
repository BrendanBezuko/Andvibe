package com.example.andvibe.github

import com.example.andvibe.agent.HttpPost
import org.json.JSONArray
import org.json.JSONObject

/** Minimal GitHub GraphQL + REST POST client over an injectable [HttpPost]. */
class GitHubApi(
    private val token: () -> String,
    private val http: HttpPost,
) {
    fun graphql(query: String, variables: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val json = JSONObject(http.post(GRAPHQL_URL, headers(), body))
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) error(describe(errors))
        return json.optJSONObject("data") ?: error("GitHub returned no data")
    }

    /** POST to a REST path like `/repos/o/r/issues`. */
    fun restPost(path: String, body: JSONObject): JSONObject =
        JSONObject(http.post(REST_URL + path, headers(), body.toString()))

    private fun headers(): Map<String, String> {
        val t = token().trim()
        if (t.isEmpty()) error(NO_TOKEN)
        return mapOf(
            "Authorization" to "Bearer $t",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
    }

    companion object {
        const val GRAPHQL_URL = "https://api.github.com/graphql"
        const val REST_URL = "https://api.github.com"
        const val NO_TOKEN = "Add a GitHub token (PAT) in Settings to sync with GitHub."
        const val SCOPE_HINT =
            "Your GitHub token can't use Projects. Give it the \"project\" scope (classic PAT) and try again."

        /** One readable line from GraphQL errors; scope problems get an actionable hint. */
        internal fun describe(errors: JSONArray): String {
            val messages = (0 until errors.length()).mapNotNull { i ->
                errors.optJSONObject(i)?.let { e ->
                    if (e.optString("type") == "INSUFFICIENT_SCOPES") return SCOPE_HINT
                    e.optString("message").takeIf { it.isNotBlank() }
                }
            }.distinct()
            val text = messages.joinToString("; ").ifBlank { "GitHub request failed" }
            if ("scope" in text.lowercase() && "project" in text.lowercase()) return SCOPE_HINT
            return text
        }
    }
}
