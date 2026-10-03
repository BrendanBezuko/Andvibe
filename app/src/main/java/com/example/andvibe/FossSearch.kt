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

    data class NewsHit(
        val title: String,
        val url: String,
        val source: String,
        val summary: String,
        val repo: RepoHit?
    )

    data class SearchResult(
        val hits: List<RepoHit>,
        val news: List<NewsHit>,
        val brief: String,
        val note: String
    )

    private val hosts = setOf("github.com", "gitlab.com", "codeberg.org", "git.sr.ht")

    private const val RANK = """
        You choose free and open-source repositories for someone about to download one onto a phone. Reply with one JSON object and nothing else:
        {"picks":[{"url":"https://host/owner/repo","why":"one short sentence"}]}
        Use only URLs from the candidate list. Return at most 5 picks, best first. Prefer a close match, a permissive license, and a project people actually use. Do not invent repositories.
    """

    private const val AGENT = """
        You research open-source software and the technology news around it. You can search the web. Reply with one JSON object and nothing else:
        {"brief":"what is current, in a few sentences","repos":[{"url":"https://github.com/owner/repo","why":"one sentence"}],"news":[{"title":"headline","url":"https://article","source":"publication","summary":"one sentence","repo":"https://github.com/owner/repo"}]}
        Prefer repositories on github.com, gitlab.com, codeberg.org, and git.sr.ht, especially ones listed as known forge results when they match. Search the web before you answer. Use only URLs you found. At most 6 repos and 6 news items. Leave repo empty when a story is not about one repository. Do not invent a project or an article.
    """

    fun search(
        query: String,
        provider: Provider,
        key: String,
        model: String,
        base: String,
        cached: List<RepoHit> = emptyList()
    ): SearchResult {
        val asked = query.trim().replace(Regex("\\s+"), " ").take(160)
        if (asked.isBlank()) error("say what you want to find")
        val known = merge(cached, knownChannels(asked))
        if (key.isNotBlank() && model.isNotBlank() && provider != Provider.CURSOR) {
            try {
                val report = agent(asked, known, provider, key, model, base)
                val repos = merge(report.repos, known)
                if (repos.isNotEmpty() || report.news.isNotEmpty()) {
                    return SearchResult(repos, report.news, report.brief, "Tap a repo to clone it. Tap a story to open it.")
                }
            } catch (t: Throwable) {
                if (known.isEmpty()) error(t.message ?: "search failed")
                return SearchResult(
                    known.take(6),
                    emptyList(),
                    "",
                    "${t.message ?: "web search failed"}. Showing GitHub, GitLab, and Codeberg."
                )
            }
        }
        if (known.isEmpty()) error("no matches on GitHub, GitLab, or Codeberg")
        val note = if (key.isBlank() || provider == Provider.CURSOR) {
            "Add a web-search provider key in Settings. These are from GitHub, GitLab, and Codeberg."
        } else {
            "Tap a repo to clone it."
        }
        return SearchResult(known.take(6), emptyList(), "", note)
    }

    private data class AgentReport(val brief: String, val repos: List<RepoHit>, val news: List<NewsHit>)

    private fun agent(
        query: String,
        known: List<RepoHit>,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): AgentReport {
        val catalog = if (known.isEmpty()) {
            "(none)"
        } else {
            known.joinToString("\n") { hit ->
                val license = hit.license.ifBlank { "unknown license" }
                "${hit.page} | ${hit.stars} stars | $license | ${hit.blurb}"
            }
        }
        val raw = AiClient.research(
            AGENT.trimIndent(),
            "Request: $query\n\nKnown forge results:\n$catalog",
            provider,
            key,
            model,
            base
        )
        val json = JSONObject(extractJson(raw))
        val repos = mutableListOf<RepoHit>()
        val picks = json.optJSONArray("repos") ?: JSONArray()
        for (i in 0 until picks.length()) {
            if (repos.size >= 6) break
            val pick = picks.optJSONObject(i) ?: continue
            val url = pick.optString("url")
            if (repos.any { same(it, url) }) continue
            val hit = resolve(url, clean(pick.optString("why")).take(180)) ?: continue
            repos.add(hit)
        }
        val news = mutableListOf<NewsHit>()
        val stories = json.optJSONArray("news") ?: JSONArray()
        for (i in 0 until stories.length()) {
            if (news.size >= 6) break
            val story = stories.optJSONObject(i) ?: continue
            val page = story.optString("url").trim()
            if (!page.startsWith("https://")) continue
            val repo = resolve(story.optString("repo"), "")
            news.add(
                NewsHit(
                    clean(story.optString("title")).take(140).ifBlank { page },
                    page,
                    clean(story.optString("source")).take(40),
                    clean(story.optString("summary")).take(220),
                    repo
                )
            )
        }
        return AgentReport(clean(json.optString("brief")).take(700), repos, news)
    }

    private fun knownChannels(query: String): List<RepoHit> {
        val found = mutableListOf<RepoHit>()
        for (load in listOf({ github(query) }, { gitlab(query) }, { codeberg(query) })) {
            try {
                found.addAll(load())
            } catch (_: Throwable) {
            }
        }
        return dedupe(found).sortedByDescending { it.stars }.take(12)
    }

    private fun merge(preferred: List<RepoHit>, extra: List<RepoHit>): List<RepoHit> {
        val out = mutableListOf<RepoHit>()
        for (hit in preferred + extra) {
            if (out.any { same(it, hit.page) }) continue
            out.add(hit)
            if (out.size >= 16) break
        }
        return out
    }

    private fun resolve(url: String, why: String): RepoHit? {
        val key = repoKey(url)
        if (key.isBlank()) return null
        val host = key.substringBefore('/')
        val path = key.substringAfter('/').split('/').filter { it.isNotBlank() }.take(2).joinToString("/")
        if (!path.contains('/')) return null
        val looked = try {
            when (host) {
                "github.com" -> githubRepo(path)
                "gitlab.com" -> gitlabRepo(path)
                "codeberg.org" -> codebergRepo(path)
                "git.sr.ht" -> sourcehutRepo(path)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        if (looked != null) return looked.copy(why = why.ifBlank { looked.blurb })
        return hit(path, "https://$host/$path.git", "https://$host/$path", 0, "", "")?.copy(why = why)
    }

    private fun githubRepo(path: String): RepoHit? {
        val item = JSONObject(getText("https://api.github.com/repos/$path"))
        val license = item.optJSONObject("license")?.optString("spdx_id").orEmpty()
        return hit(
            item.optString("full_name").ifBlank { path },
            item.optString("clone_url").ifBlank { "https://github.com/$path.git" },
            item.optString("html_url").ifBlank { "https://github.com/$path" },
            item.optInt("stargazers_count"),
            item.optString("description"),
            if (license == "NOASSERTION") "" else license
        )
    }

    private fun gitlabRepo(path: String): RepoHit? {
        val item = JSONObject(getText("https://gitlab.com/api/v4/projects/${enc(path)}"))
        return hit(
            item.optString("path_with_namespace").ifBlank { path },
            item.optString("http_url_to_repo"),
            item.optString("web_url"),
            item.optInt("star_count"),
            item.optString("description"),
            ""
        )
    }

    private fun sourcehutRepo(path: String): RepoHit? {
        val user = path.substringBefore('/')
        val name = path.substringAfter('/').substringBefore('/')
        if (!user.startsWith("~") || name.isBlank()) return null
        val item = JSONObject(getText("https://git.sr.ht/api/$user/repos/$name"))
        val repoName = item.optString("name").ifBlank { name }
        return hit(
            "$user/$repoName",
            "https://git.sr.ht/$user/$repoName",
            "https://git.sr.ht/$user/$repoName",
            0,
            item.optString("description"),
            ""
        )
    }

    private fun codebergRepo(path: String): RepoHit? {
        val item = JSONObject(getText("https://codeberg.org/api/v1/repos/$path"))
        return hit(
            item.optString("full_name").ifBlank { path },
            item.optString("clone_url"),
            item.optString("html_url"),
            item.optInt("stars_count"),
            item.optString("description"),
            ""
        )
    }

    private fun forge(
        asked: String,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): SearchResult {
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
                emptyList(),
                "",
                "Add an API key in Settings to search the web. Tap one to clone it."
            )
        }
        return try {
            val ranked = rank(asked, hits, provider, key, model, base)
            if (ranked.isEmpty()) {
                SearchResult(hits.take(6).map { it.copy(why = it.blurb) }, emptyList(), "", "Tap one to clone it.")
            } else {
                SearchResult(ranked, emptyList(), "", "Tap one to clone it.")
            }
        } catch (_: Throwable) {
            SearchResult(
                hits.take(6).map { it.copy(why = it.blurb) },
                emptyList(),
                "",
                "Tap one to clone it."
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
