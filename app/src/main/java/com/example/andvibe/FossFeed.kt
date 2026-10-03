package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object FossFeed {
    data class Channel(val group: String, val name: String, val url: String, val detail: String)

    private const val DAY = 24L * 60L * 60L * 1000L
    private val topics = listOf(
        "android", "llm", "rust", "self-hosted", "embedded", "electronics", "homelab", "esp32"
    )

    val channels = listOf(
        Channel("GitHub", "Trending, this week", "https://github.com/trending?since=weekly", "Weekly is less noisy than today."),
        Channel("GitHub", "Explore", "https://github.com/explore", "Topics, collections, and projects."),
        Channel("GitHub", "Topics", "https://github.com/topics", "self-hosted, embedded, llm, rust, android, electronics, homelab."),
        Channel("Awesome", "sindresorhus/awesome", "https://github.com/sindresorhus/awesome", "The index of curated lists."),
        Channel("Awesome", "Awesome Index", "https://awesomeindex.dev", "Search the projects inside awesome lists."),
        Channel("Awesome", "Open Awesome", "https://open-awesome.com", "Awesome lists with stars and recent commits."),
        Channel("Awesome", "awesome-llm", "https://github.com/Hannibal046/Awesome-LLM", "AI / LLM"),
        Channel("Awesome", "awesome-generative-ai", "https://github.com/steven2358/awesome-generative-ai", "AI / LLM"),
        Channel("Awesome", "awesome-rag", "https://github.com/search?q=awesome-rag&type=repositories&s=stars&o=desc", "AI / LLM"),
        Channel("Awesome", "awesome-local-ai", "https://github.com/search?q=awesome-local-ai&type=repositories&s=stars&o=desc", "AI / LLM"),
        Channel("Awesome", "awesome-ai-agents", "https://github.com/e2b-dev/awesome-ai-agents", "AI / LLM"),
        Channel("Awesome", "awesome-selfhosted", "https://github.com/awesome-selfhosted/awesome-selfhosted", "Software engineering"),
        Channel("Awesome", "awesome-cli-apps", "https://github.com/agarrharr/awesome-cli-apps", "Software engineering"),
        Channel("Awesome", "awesome-rust", "https://github.com/rust-unofficial/awesome-rust", "Software engineering"),
        Channel("Awesome", "awesome-python", "https://github.com/vinta/awesome-python", "Software engineering"),
        Channel("Awesome", "awesome-typescript", "https://github.com/dzharii/awesome-typescript", "Software engineering"),
        Channel("Awesome", "awesome-devops", "https://github.com/search?q=awesome-devops&type=repositories&s=stars&o=desc", "Software engineering"),
        Channel("Awesome", "awesome-embedded", "https://github.com/search?q=awesome-embedded&type=repositories&s=stars&o=desc", "Electronics / embedded"),
        Channel("Awesome", "awesome-electronics", "https://github.com/kitspace/awesome-electronics", "Electronics / embedded"),
        Channel("Awesome", "awesome-hardware", "https://github.com/search?q=awesome-hardware&type=repositories&s=stars&o=desc", "Electronics / embedded"),
        Channel("Awesome", "awesome-esp32", "https://github.com/search?q=awesome-esp32&type=repositories&s=stars&o=desc", "Electronics / embedded"),
        Channel("Awesome", "awesome-mcu", "https://github.com/search?q=awesome-mcu&type=repositories&s=stars&o=desc", "Electronics / embedded"),
        Channel("Awesome", "awesome-kicad", "https://github.com/search?q=awesome-kicad&type=repositories&s=stars&o=desc", "Electronics / embedded"),
        Channel("Awesome", "awesome-android", "https://github.com/JStumpp/awesome-android", "Android"),
        Channel("Directories", "FOSSY", "https://fossy.dev/trending", "Curated FOSS ranked by live GitHub activity."),
        Channel("Directories", "OpenCurious", "https://www.opencurious.com/explore-open-source", "Searchable index by area: AI, robotics, mobile, DevOps, security."),
        Channel("Directories", "OSS Insight", "https://ossinsight.io/trending", "Star velocity and growth, not just the biggest star counts."),
        Channel("Forges", "Codeberg", "https://codeberg.org/explore/repos", "Nonprofit Forgejo forge."),
        Channel("Forges", "SourceHut", "https://sr.ht", "Minimal forge. Email patches are common."),
        Channel("Forges", "GitLab", "https://gitlab.com/explore/projects", "Larger projects and self-hosted forges."),
        Channel("Android", "F-Droid", "https://f-droid.org", "Free Android apps."),
        Channel("Android", "IzzyOnDroid", "https://apt.izzysoft.de/fdroid", "Companion Android catalog."),
        Channel("Android", "Topic: android", "https://github.com/topics/android", "GitHub topic.")
    )

    fun stale(context: Context): Boolean {
        val at = read(context).optLong("fetchedAt", 0L)
        return at <= 0L || System.currentTimeMillis() - at > DAY
    }

    fun status(context: Context): String {
        val at = read(context).optLong("fetchedAt", 0L)
        if (at <= 0L) return "Channels stay on this phone. The weekly project list refreshes once a day."
        val hours = ((System.currentTimeMillis() - at) / 3_600_000L).coerceAtLeast(0L)
        return if (hours < 1) "Weekly list cached just now." else "Weekly list cached ${hours}h ago."
    }

    fun projects(context: Context): List<FossSearch.RepoHit> {
        val items = read(context).optJSONArray("items") ?: return emptyList()
        val seen = linkedSetOf<String>()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val page = item.optString("page")
                val clone = item.optString("clone")
                if (name.isBlank() || page.isBlank() || clone.isBlank()) continue
                if (!seen.add(page.lowercase())) continue
                add(
                    FossSearch.RepoHit(
                        name,
                        clone,
                        page,
                        item.optInt("stars"),
                        item.optString("blurb"),
                        "",
                        item.optString("source")
                    )
                )
            }
        }
    }

    fun matching(context: Context, query: String): List<FossSearch.RepoHit> {
        val words = query.lowercase().split(Regex("[^a-z0-9+#.]+")).filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        return projects(context).filter { hit ->
            val hay = "${hit.name} ${hit.blurb} ${hit.why}".lowercase()
            words.any { hay.contains(it) }
        }.take(8)
    }

    fun refresh(context: Context): String {
        val items = JSONArray()
        val problems = mutableListOf<String>()
        fun addAll(source: String, loader: () -> List<FossSearch.RepoHit>) {
            try {
                for (hit in loader()) {
                    items.put(
                        JSONObject()
                            .put("source", source)
                            .put("name", hit.name)
                            .put("page", hit.page)
                            .put("clone", hit.cloneUrl)
                            .put("blurb", hit.blurb)
                            .put("stars", hit.stars)
                    )
                }
            } catch (t: Throwable) {
                problems.add("$source: ${t.message ?: "failed"}")
            }
        }
        val day = dayStamp(-7)
        addAll("GitHub this week") { githubSearch("created:>$day stars:>20", "stars") }
        val topic = topics[((System.currentTimeMillis() / DAY) % topics.size).toInt()]
        addAll("Topic: $topic") { githubSearch("topic:$topic stars:>30", "updated") }
        addAll("Codeberg") { codebergRecent() }
        addAll("GitLab") { gitlabRecent() }
        val file = file(context)
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("fetchedAt", System.currentTimeMillis()).put("items", items).toString())
        val extra = if (problems.isEmpty()) "" else " " + problems.joinToString("; ")
        return "Cached ${items.length()} projects from GitHub, Codeberg, and GitLab.$extra"
    }

    private fun githubSearch(query: String, sort: String): List<FossSearch.RepoHit> {
        val url = "https://api.github.com/search/repositories?q=${enc(query)}&sort=$sort&order=desc&per_page=6"
        val rows = JSONObject(getText(url)).optJSONArray("items") ?: return emptyList()
        return readRepos(rows) { item ->
            val license = item.optJSONObject("license")?.optString("spdx_id").orEmpty()
            repo(
                item.optString("full_name"),
                item.optString("clone_url"),
                item.optString("html_url"),
                item.optInt("stargazers_count"),
                item.optString("description"),
                if (license == "NOASSERTION") "" else license
            )
        }
    }

    private fun codebergRecent(): List<FossSearch.RepoHit> {
        val text = getText("https://codeberg.org/api/v1/repos/search?q=${enc("app")}&sort=updated&order=desc&limit=5")
        val rows = if (text.trimStart().startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("data") ?: JSONArray()
        }
        return readRepos(rows) { item ->
            repo(
                item.optString("full_name"),
                item.optString("clone_url"),
                item.optString("html_url"),
                item.optInt("stars_count"),
                item.optString("description"),
                ""
            )
        }
    }

    private fun gitlabRecent(): List<FossSearch.RepoHit> {
        val url = "https://gitlab.com/api/v4/projects?visibility=public&order_by=last_activity_at&sort=desc&per_page=5"
        return readRepos(JSONArray(getText(url))) { item ->
            repo(
                item.optString("path_with_namespace"),
                item.optString("http_url_to_repo"),
                item.optString("web_url"),
                item.optInt("star_count"),
                item.optString("description"),
                ""
            )
        }
    }

    private fun readRepos(items: JSONArray, map: (JSONObject) -> FossSearch.RepoHit?): List<FossSearch.RepoHit> {
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val hit = map(item) ?: continue
                add(hit)
            }
        }
    }

    private fun repo(
        name: String,
        cloneUrl: String,
        page: String,
        stars: Int,
        blurb: String,
        license: String
    ): FossSearch.RepoHit? {
        if (name.isBlank() || !cloneUrl.startsWith("https://")) return null
        return FossSearch.RepoHit(
            name.trim(),
            cloneUrl.trim(),
            page.trim().ifBlank { cloneUrl.removeSuffix(".git") },
            stars.coerceAtLeast(0),
            blurb.replace(Regex("\\s+"), " ").trim().take(160),
            license.trim(),
            ""
        )
    }

    private fun read(context: Context): JSONObject {
        val file = file(context)
        if (!file.isFile) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun file(context: Context) = File(context.filesDir, "foss-feed.json")

    private fun dayStamp(offsetDays: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AndVibe")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
