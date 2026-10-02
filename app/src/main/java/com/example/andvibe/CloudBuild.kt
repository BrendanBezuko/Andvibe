package com.example.andvibe

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object CloudBuild {
    private const val MAX_FILE = 8 * 1024 * 1024
    private const val MAX_ZIP = 32 * 1024 * 1024

    fun build(context: Context, root: File, serviceUrl: String, token: String, log: (String) -> Unit): File {
        val started = System.currentTimeMillis()
        DebugLog.step("cloud", "start root=${root.absolutePath}")
        val endpoint = endpoint(serviceUrl)
        DebugLog.step("cloud", "endpoint host=${hostOf(endpoint)}")
        val zip = File(context.cacheDir, "cloud-src.zip")
        val result = File(context.cacheDir, "cloud-result.bin")
        try {
            log("zipping ${root.name}")
            val size = writeZip(root, zip, log)
            log("uploading ${size / 1024} KB to $endpoint")
            log("Cloud Run is compiling. Keep AndVibe open. The first build can take several minutes.")
            val apk = post(context, endpoint, token, zip, result, root, log)
            DebugLog.step("cloud", "done ${System.currentTimeMillis() - started}ms path=${apk.absolutePath} bytes=${apk.length()}")
            return apk
        } catch (t: Throwable) {
            DebugLog.step("cloud", "fail ${System.currentTimeMillis() - started}ms ${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            zip.delete()
            result.delete()
        }
    }

    private fun endpoint(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            error("Cloud Run URL must start with https://")
        }
        if (!url.endsWith("/build") && !url.contains("/build?")) url += "/build"
        if (!url.contains("task=")) {
            url += if (url.contains("?")) "&task=assembleDebug" else "?task=assembleDebug"
        }
        return url
    }

    private fun writeZip(root: File, dest: File, log: (String) -> Unit): Long {
        DebugLog.step("cloud.zip", "start ${root.name}")
        if (dest.exists()) dest.delete()
        var count = 0
        var skipped = 0
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            RepoFiles.walk(root) { file ->
                if (file.name == "local.properties") {
                    DebugLog.step("cloud.zip", "skip local.properties")
                    return@walk
                }
                val rel = RepoFiles.rel(file, root)
                if (rel.isBlank() || rel.split('/').any { it == ".." || it.isBlank() }) {
                    DebugLog.step("cloud.zip", "skip unsafe $rel")
                    return@walk
                }
                if (file.length() > MAX_FILE) {
                    skipped++
                    DebugLog.step("cloud.zip", "skip large $rel bytes=${file.length()}")
                    return@walk
                }
                val entry = ZipEntry(rel)
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        if (skipped > 0) log("skipped $skipped large files")
        log("zipped $count files")
        DebugLog.step("cloud.zip", "done files=$count skipped=$skipped bytes=${dest.length()}")
        if (dest.length() > MAX_ZIP) {
            error("zip is ${dest.length() / 1024 / 1024}MB. Cloud Run allows 32MB. Leave out big assets.")
        }
        if (count == 0) error("nothing to upload")
        return dest.length()
    }

    private fun post(
        context: Context,
        endpoint: String,
        token: String,
        zip: File,
        result: File,
        root: File,
        log: (String) -> Unit
    ): File {
        val started = System.currentTimeMillis()
        DebugLog.step("cloud.http", "connect bytes=${zip.length()}")
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 55 * 60 * 1000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/zip")
            setRequestProperty("User-Agent", "AndVibe")
            setFixedLengthStreamingMode(zip.length())
        }
        try {
            try {
                zip.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
                DebugLog.step("cloud.http", "upload sent ${System.currentTimeMillis() - started}ms")
            } catch (t: SocketTimeoutException) {
                DebugLog.step("cloud.http", "upload timeout ${System.currentTimeMillis() - started}ms")
                error("could not reach Cloud Run")
            }
            val code = conn.responseCode
            DebugLog.step("cloud.http", "status=$code ${System.currentTimeMillis() - started}ms")
            if (code == 401) error("Cloud Run rejected the token")
            if (code !in 200..299) {
                val err = readLimited(conn.errorStream)
                error(if (err.isBlank()) "HTTP $code" else "HTTP $code\n$err")
            }
            result.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            val type = conn.contentType.orEmpty()
            val suggested = headerName(conn) ?: "${safe(root.name)}-debug.apk"
            DebugLog.step(
                "cloud.http",
                "body bytes=${result.length()} type=$type name=$suggested ${System.currentTimeMillis() - started}ms"
            )
            log("download finished")
            return saveResult(context, result, type, suggested)
        } catch (t: SocketTimeoutException) {
            error("the phone stopped waiting. Cloud Run may still be building.")
        } finally {
            conn.disconnect()
        }
    }

    private fun saveResult(context: Context, tmp: File, type: String, suggested: String): File {
        val outDir = context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk")
        outDir.mkdirs()
        val zipResult = type.contains("zip") || suggested.endsWith(".zip")
        if (!zipResult) {
            val dest = File(outDir, safe(suggested))
            tmp.copyTo(dest, overwrite = true)
            DebugLog.step("cloud.save", "apk path=${dest.absolutePath} bytes=${dest.length()}")
            return dest
        }
        ZipFile(tmp).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk") }
            val pick = entries.firstOrNull { it.name.endsWith("app-debug.apk") } ?: entries.firstOrNull()
                ?: error("Cloud Run returned a zip with no APK")
            val dest = File(outDir, safe(File(pick.name).name))
            zip.getInputStream(pick).use { input -> dest.outputStream().use { input.copyTo(it) } }
            DebugLog.step("cloud.save", "apk path=${dest.absolutePath} bytes=${dest.length()} from=${pick.name}")
            return dest
        }
    }

    private fun headerName(conn: HttpURLConnection): String? {
        val raw = conn.getHeaderField("Content-Disposition") ?: return null
        val marker = "filename="
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        return raw.substring(idx + marker.length).trim().trim('"').substringAfterLast('/').substringAfterLast('\\')
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader().use { reader ->
            val buf = CharArray(4096)
            val out = StringBuilder()
            while (out.length < 120_000) {
                val n = reader.read(buf)
                if (n < 0) break
                out.append(buf, 0, n)
            }
            out.toString().trim()
        }
    }

    private fun hostOf(url: String): String {
        return try {
            URL(url).host.ifBlank { url }
        } catch (_: Exception) {
            "bad-url"
        }
    }

    private fun safe(name: String): String {
        val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return clean.ifBlank { "app-debug.apk" }
    }
}
