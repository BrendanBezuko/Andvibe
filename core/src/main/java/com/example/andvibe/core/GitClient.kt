package com.example.andvibe.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.EmptyProgressMonitor
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory

object GitClient {
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val MAX_TOTAL = 200L * 1024 * 1024

    fun normalizeGitUrl(raw: String): String {
        val url = raw.trim()
        if (url.startsWith("git@") || url.startsWith("ssh://")) {
            error("SSH is not supported. Use an https URL.")
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            error("Use an https URL.")
        }
        return url
    }

    fun safeRepoName(name: String): String {
        val clean = name.trim().filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (clean.isBlank() || clean == "." || clean == "..") error("bad directory name")
        return clean
    }

    fun repoNameFromUrl(url: String): String {
        val path = try {
            URI(url).path
        } catch (_: Exception) {
            url
        }
        val name = path.trim('/').substringAfterLast('/').removeSuffix(".git")
        return safeRepoName(name.ifBlank { "repo" })
    }

    fun clone(url: String, dest: File, log: (String) -> Unit) {
        if (dest.exists() && !dest.list().isNullOrEmpty()) error("already exists: ${dest.name}")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        try {
            log("cloning $url")
            tryJgit(url, dest, log)
            log("cloned into ~/${dest.name}")
        } catch (failure: Exception) {
            dest.deleteRecursively()
            log("git protocol failed (${failure.message ?: failure.javaClass.simpleName})")
            downloadSnapshot(url, dest, log)
        }
    }

    fun status(start: File, repos: File): String = GitOps.status(start, repos)

    fun pull(start: File, repos: File): String = GitOps.pull(start, repos)

    private fun tryJgit(url: String, dest: File, log: (String) -> Unit) {
        try {
            prepare()
            try {
                doClone(url, dest, log, shallow = true)
            } catch (t: Throwable) {
                dest.deleteRecursively()
                if (isBroken(t)) throw t
                log("retrying without a shallow clone")
                doClone(url, dest, log, shallow = false)
            }
        } catch (t: Throwable) {
            dest.deleteRecursively()
            throw IllegalStateException(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun doClone(url: String, dest: File, log: (String) -> Unit, shallow: Boolean) {
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(dest)
            .setCloneSubmodules(false)
            .setProgressMonitor(object : EmptyProgressMonitor() {
                override fun beginTask(title: String, totalWork: Int) {
                    if (title.isNotBlank()) log(title)
                }
            })
        if (shallow) cmd.setDepth(1)
        cmd.call().close()
    }

    private fun prepare() {
        System.setProperty("jgit.fs.useFileAttributesCache", "false")
        HttpTransport.setConnectionFactory(JDKHttpConnectionFactory())
    }

    private fun isBroken(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is ClassNotFoundException ||
                current is NoClassDefFoundError ||
                current is ExceptionInInitializerError
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun downloadSnapshot(url: String, dest: File, log: (String) -> Unit) {
        val candidates = archiveCandidates(url)
        if (candidates.isEmpty()) {
            error(
                "clone failed. Archive download supports github.com, gitlab.com, codeberg.org, and bitbucket.org."
            )
        }
        log("downloading a snapshot instead")
        var last = "download failed"
        for (candidate in candidates) {
            try {
                log("fetch $candidate")
                unpack(candidate, dest)
                log("downloaded into ~/${dest.name} (snapshot, no git history)")
                return
            } catch (e: Exception) {
                dest.deleteRecursively()
                last = e.message ?: last
                log("failed: $last")
            }
        }
        error(last)
    }

    fun archiveCandidates(raw: String): List<String> {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return emptyList()
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return emptyList()
        val parts = projectParts(uri.path ?: return emptyList())
        if (parts.size < 2) return emptyList()
        val owner = seg(parts[0])
        val repo = seg(parts[1])
        return when (host) {
            "github.com" -> githubCandidates(uri.path ?: "", owner, repo)
            "gitlab.com" -> listOf(
                "https://gitlab.com/api/v4/projects/${enc(parts.joinToString("/"))}/repository/archive.zip"
            )
            "codeberg.org" -> listOf(
                "https://codeberg.org/$owner/$repo/archive/main.zip",
                "https://codeberg.org/$owner/$repo/archive/master.zip"
            )
            "bitbucket.org" -> listOf("https://bitbucket.org/$owner/$repo/get/HEAD.zip")
            else -> emptyList()
        }
    }

    private fun githubCandidates(path: String, owner: String, repo: String): List<String> {
        val raw = path.split('/').filter { it.isNotBlank() }.map { it.removeSuffix(".git") }
        val treeAt = raw.indexOf("tree")
        val branch = if (treeAt >= 0 && treeAt + 1 < raw.size) seg(raw[treeAt + 1]) else null
        return if (branch != null) {
            listOf("https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch")
        } else {
            listOf(
                "https://api.github.com/repos/$owner/$repo/zipball",
                "https://codeload.github.com/$owner/$repo/zip/refs/heads/main",
                "https://codeload.github.com/$owner/$repo/zip/refs/heads/master"
            )
        }
    }

    private fun projectParts(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotBlank() }.map { it.removeSuffix(".git") }
        val stop = parts.indexOfFirst {
            it == "-" || it == "tree" || it == "blob" || it == "commit" || it == "releases"
        }
        val kept = if (stop == -1) parts else parts.subList(0, stop)
        return kept.filter { it != "-" }
    }

    private fun seg(value: String): String = enc(value)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun unpack(url: String, dest: File) {
        if (dest.exists()) dest.deleteRecursively()
        val parent = dest.parentFile ?: error("bad destination")
        parent.mkdirs()
        val tmp = File(parent, ".${dest.name}.part")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            downloadZip(url, tmp)
            moveFlattened(tmp, dest)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun downloadZip(url: String, dir: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("User-Agent", "AndVibe")
            setRequestProperty("Accept", "application/vnd.github+json, application/zip, */*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP $code ${err.take(180)}")
            }
            var total = 0L
            ZipInputStream(BufferedInputStream(conn.inputStream)).use { zip ->
                val root = dir.canonicalFile
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank() || name.contains("__MACOSX")) {
                        zip.closeEntry()
                        continue
                    }
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                        error("blocked unsafe zip entry")
                    }
                    val out = File(dir, name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        error("blocked unsafe zip entry")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        var entryBytes = 0L
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                total += n
                                if (entryBytes > MAX_ENTRY || total > MAX_TOTAL) {
                                    error("repo is too large to unpack on this phone")
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun moveFlattened(tmp: File, dest: File) {
        val kids = tmp.listFiles()?.filter { it.name != "__MACOSX" }.orEmpty()
        if (kids.isEmpty()) error("empty archive")
        val source = if (kids.size == 1 && kids[0].isDirectory) kids[0] else tmp
        val moving = source.listFiles()?.toList().orEmpty()
        if (moving.isEmpty()) error("empty archive")
        dest.mkdirs()
        for (child in moving) {
            val target = File(dest, child.name)
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
    }
}
