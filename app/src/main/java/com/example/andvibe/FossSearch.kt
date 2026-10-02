package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

object FossSearch {
    data class RepoHit(
        val name: String,
        val cloneUrl: String,
        val page: String,
        val stars: Int,
        val blurb: String,
        val license: String,
        val why: String
    )

    data class SearchResult(val hits: List<RepoHit>, val note: String)

    private val hosts = setOf("github.com", "gitlab.com", "codeberg.org")

    private const val RANK = """
        You choose free and open-source repositories for someone about to download one onto a phone. Reply with one JSON object and nothing else:
        {"picks":[{"url":"https://host/owner/repo","why":"one short sentence"}]}
        Use only URLs from the candidate list. Return at most 5 picks, best first. Prefer a close match, a permissive license, and a project people actually use. Do not invent repositories.
    """

    fun search(
        query: String,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): SearchResult {
        val asked = query.trim().replace(Regex("\\s+"), " ").take(120)
        if (asked.isBlank()) error("say what you want to find")
        val found = mutableListOf<RepoHit>()
        val problems = mutableListOf<String>()
        for ((label, load) in listOf(
            "GitHub" to { github(asked) },
            "GitLab" to { gitlab(asked) },
            "Codeberg" to { codeberg(asked) }
        )) {
            try {
                found.addAll(load())
            } catch (t: Throwable) {
                problems.add("$label: ${t.message ?: "failed"}")
            }
        }
        val hits = dedupe(found).sortedByDescending { it.stars }
        if (hits.isEmpty()) {
            val detail = problems.joinToString("; ").ifBlank { "no matches" }
            error(detail)
        }
        if (key.isBlank() || model.isBlank()) {
            return SearchResult(
                hits.take(6).map { it.copy(why = it.blurb) },
                "Add an API key in Settings to rank these. Tap one to download it."
            )
        }
        return try {
            val ranked = rank(asked, hits, provider, key, model, base)
            if (ranked.isEmpty()) {
                SearchResult(hits.take(6).map { it.copy(why = it.blurb) }, "Tap one to download it.")
            } else {
                SearchResult(ranked, "Ranked for this search. Tap one to download it.")
            }
        } catch (t: Throwable) {
            SearchResult(
                hits.take(6).map { it.copy(why = it.blurb) },
                "Could not rank these. Tap one to download it."
            )
        }
    }

    private fun rank(
        query: String,
        hits: List<RepoHit>,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): List<RepoHit> {
        val listed = hits.take(12)
        val catalog = listed.joinToString("\n") { hit ->
            val license = hit.license.ifBlank { "unknown license" }
            "${hit.page} | ${hit.stars} stars | $license | ${hit.blurb}"
        }
        val raw = AiClient.complete(
            RANK.trimIndent(),
            "Request: $query\n\nCandidates:\n$catalog",
            provider,
            key,
            model,
            base
        )
        val picks = JSONObject(extractJson(raw)).optJSONArray("picks") ?: return emptyList()
        val out = mutableListOf<RepoHit>()
        for (i in 0 until picks.length()) {
            if (out.size >= 5) break
            val pick = picks.optJSONObject(i) ?: continue
            val match = listed.firstOrNull { same(it, pick.optString("url")) } ?: continue
            if (out.any { same(it, match.page) }) continue
            val why = clean(pick.optString("why")).take(180).ifBlank { match.blurb }
            out.add(match.copy(why = why))
        }
        return out
    }

    private fun github(query: String): List<RepoHit> {
        val url = "https://api.github.com/search/repositories?q=${enc("$query fork:false")}&sort=stars&order=desc&per_page=8"
        val items = JSONObject(getText(url)).optJSONArray("items") ?: return emptyList()
        return read(items) { item ->
            val license = item.optJSONObject("license")?.optString("spdx_id").orEmpty()
            hit(
                item.optString("full_name"),
                item.optString("clone_url"),
                item.optString("html_url"),
                item.optInt("stargazers_count"),
                item.optString("description"),
                if (license == "NOASSERTION") "" else license
            )
        }
    }

    private fun gitlab(query: String): List<RepoHit> {
        val url = "https://gitlab.com/api/v4/projects?search=${enc(query)}&order_by=star_count&sort=desc&visibility=public&per_page=5"
        return read(JSONArray(getText(url))) { item ->
            hit(
                item.optString("path_with_namespace"),
                item.optString("http_url_to_repo"),
                item.optString("web_url"),
                item.optInt("star_count"),
                item.optString("description"),
                ""
            )
        }
    }

    private fun codeberg(query: String): List<RepoHit> {
        val url = "https://codeberg.org/api/v1/repos/search?q=${enc(query)}&sort=stars&order=desc&limit=5"
        val data = JSONObject(getText(url)).optJSONArray("data") ?: return emptyList()
        return read(data) { item ->
            hit(
                item.optString("full_name"),
                item.optString("clone_url"),
                item.optString("html_url"),
                item.optInt("stars_count"),
                item.optString("description"),
                ""
            )
        }
    }

    private fun read(items: JSONArray, map: (JSONObject) -> RepoHit?): List<RepoHit> {
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val hit = map(item) ?: continue
                add(hit)
            }
        }
    }

    private fun hit(
        name: String,
        cloneUrl: String,
        page: String,
        stars: Int,
        blurb: String,
        license: String
    ): RepoHit? {
        val clone = cloneUrl.trim()
        val web = page.trim().ifBlank { clone.removeSuffix(".git") }
        if (name.isBlank() || !allowed(clone)) return null
        return RepoHit(name.trim(), clone, web, stars.coerceAtLeast(0), clean(blurb).take(160), license.trim(), "")
    }

    private fun dedupe(hits: List<RepoHit>): List<RepoHit> {
        val seen = linkedMapOf<String, RepoHit>()
        for (hit in hits) {
            val key = repoKey(hit.cloneUrl)
            val current = seen[key]
            if (current == null || hit.stars > current.stars) seen[key] = hit
        }
        return seen.values.toList()
    }

    private fun same(hit: RepoHit, url: String): Boolean {
        val key = repoKey(url)
        return key.isNotBlank() && (key == repoKey(hit.page) || key == repoKey(hit.cloneUrl))
    }

    private fun repoKey(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return ""
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return ""
        if (host !in hosts) return ""
        val path = uri.path.orEmpty().trim('/').removeSuffix(".git").lowercase()
        if (path.isBlank()) return ""
        return "$host/$path"
    }

    private fun allowed(url: String): Boolean {
        return url.startsWith("https://") && repoKey(url).isNotBlank()
    }

    private fun clean(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun extractJson(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text)
            if (text.trimEnd().endsWith("```")) text = text.trimEnd().dropLast(3)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("model did not return JSON")
        return text.substring(start, end + 1)
    }

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
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            error("network: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
