package com.example.andvibe

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolve and download Termux main-repo .debs (Bionic aarch64 / arm).
 * Used for on-device JDK + aapt/aapt2 natives.
 */
object TermuxPackages {
    private const val BASE = "https://packages.termux.dev/apt/termux-main"
    private val ROOTS = listOf("openjdk-17", "aapt2", "aapt")
    private val SKIP = setOf(
        "openjdk-17-x", "openjdk-17-source", "alsa-plugins", "xorgproto", "libx11",
        "libxext", "libxrender", "libxtst", "libxi",
    )

    data class Pkg(
        val name: String,
        val version: String,
        val filename: String,
        val depends: List<String>,
        val size: Long,
    ) {
        val url: String get() = "$BASE/$filename"
    }

    fun archFolder(abi: String): String = when (abi) {
        "arm64-v8a" -> "binary-aarch64"
        "armeabi-v7a" -> "binary-arm"
        else -> error("Termux packages are only wired for arm64-v8a / armeabi-v7a (got $abi)")
    }

    fun fetchIndex(abi: String, log: (String) -> Unit): Map<String, Pkg> {
        val url = "$BASE/dists/stable/main/${archFolder(abi)}/Packages"
        log("Fetching Termux package index…")
        DebugLog.step("toolchain", "GET $url")
        val text = httpGetText(url)
        return parsePackages(text)
    }

    fun resolveClosure(index: Map<String, Pkg>, roots: List<String> = ROOTS): List<Pkg> {
        val out = LinkedHashMap<String, Pkg>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in SKIP || name in out) continue
            val pkg = index[name] ?: continue
            out[name] = pkg
            for (dep in pkg.depends) {
                if (dep !in out && dep !in SKIP) queue.add(dep)
            }
        }
        for (root in roots) {
            if (root !in out) error("Termux package missing from index: $root")
        }
        return out.values.toList()
    }

    fun downloadAll(pkgs: List<Pkg>, dir: File, log: (String) -> Unit): List<File> {
        dir.mkdirs()
        val files = ArrayList<File>(pkgs.size)
        var i = 0
        for (pkg in pkgs) {
            i++
            val dest = File(dir, pkg.filename.substringAfterLast('/'))
            log("($i/${pkgs.size}) ${pkg.name} ${pkg.version} (${pkg.size / 1024} KB)")
            httpGetFile(pkg.url, dest, log)
            files.add(dest)
        }
        return files
    }

    internal fun parsePackages(text: String): Map<String, Pkg> {
        val out = LinkedHashMap<String, Pkg>()
        for (block in text.split("\n\n")) {
            if (block.isBlank()) continue
            var name = ""
            var version = ""
            var filename = ""
            var depends = ""
            var size = 0L
            for (line in block.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
                    line.startsWith("Version: ") -> version = line.removePrefix("Version: ").trim()
                    line.startsWith("Filename: ") -> filename = line.removePrefix("Filename: ").trim()
                    line.startsWith("Depends: ") -> depends = line.removePrefix("Depends: ").trim()
                    line.startsWith("Size: ") -> size = line.removePrefix("Size: ").trim().toLongOrNull() ?: 0L
                }
            }
            if (name.isBlank() || filename.isBlank()) continue
            out[name] = Pkg(name, version, filename, parseDepends(depends), size)
        }
        return out
    }

    private fun parseDepends(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim().substringBefore('|').substringBefore('(').trim() }
            .filter { it.isNotBlank() && it !in SKIP }
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "AndVibe")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("Termux index HTTP ${conn.responseCode}")
            return BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetFile(url: String, dest: File, log: (String) -> Unit) {
        DebugLog.step("toolchain", "GET $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 600_000
            setRequestProperty("User-Agent", "AndVibe")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("download failed HTTP ${conn.responseCode}: $url")
            }
            dest.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - last > 8L * 1024 * 1024) {
                            log("… ${total / (1024 * 1024)} MB")
                            last = total
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
