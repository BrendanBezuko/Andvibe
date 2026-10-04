package com.example.andvibe

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object CloudBuild {
    private const val MAX_FILE = 8 * 1024 * 1024
    private const val MAX_ZIP = 32 * 1024 * 1024

    fun build(
        context: Context,
        root: File,
        serviceUrl: String,
        token: String,
        log: (String) -> Unit,
    ): File {
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
            log("Cloud Run is compiling. The log streams here. You can leave AndVibe; a notification pings when it is done.")
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
        log: (String) -> Unit,
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
            setRequestProperty("Accept", "application/x-ndjson")
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
            val type = conn.contentType.orEmpty()
            val suggested = headerName(conn) ?: "${safe(root.name)}-debug.apk"
            if (type.contains("ndjson")) {
                DebugLog.step("cloud.http", "stream type=$type ${System.currentTimeMillis() - started}ms")
                val apk = readStream(context, conn.inputStream, suggested, log)
                DebugLog.step(
                    "cloud.http",
                    "apk bytes=${apk.length()} ${System.currentTimeMillis() - started}ms"
                )
                log("download finished")
                return apk
            }
            result.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
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

    private fun readStream(
        context: Context,
        input: InputStream,
        suggested: String,
        log: (String) -> Unit,
    ): File {
        while (true) {
            val line = readLineLimited(input) ?: break
            if (line.isBlank()) continue
            val json = try {
                JSONObject(line)
            } catch (_: Exception) {
                log(line.take(500))
                continue
            }
            when (json.optString("event")) {
                "log" -> {
                    val text = json.optString("text")
                    if (text.isNotBlank()) log(text)
                }
                "apk" -> {
                    val bytes = json.optLong("bytes", -1)
                    if (bytes <= 0L || bytes > 64L * 1024 * 1024) error("Cloud Run sent a bad APK size")
                    val name = json.optString("name").ifBlank { suggested }
                    val tmp = File(context.cacheDir, "cloud-apk.bin")
                    try {
                        tmp.outputStream().use { out -> copyExact(input, out, bytes) }
                        val type = if (name.endsWith(".zip")) "application/zip" else "application/vnd.android.package-archive"
                        return saveResult(context, tmp, type, name)
                    } finally {
                        tmp.delete()
                    }
                }
                "error" -> error(json.optString("text").ifBlank { "build failed" })
                else -> {
                    val text = json.optString("text")
                    if (text.isNotBlank()) log(text)
                }
            }
        }
        error("Cloud Run closed before an APK")
    }

    private fun readLineLimited(input: InputStream, max: Int = 2_000_000): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < max) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return out.toString(Charsets.UTF_8.name())
            if (b != '\r'.code) out.write(b)
        }
        error("Cloud Run sent a line that is too long")
    }

    private fun copyExact(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("APK download ended early")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun saveResult(context: Context, tmp: File, type: String, suggested: String): File {
        val zipResult = type.contains("zip") || suggested.endsWith(".zip")
        if (!zipResult) {
            val dest = ApkLibrary.place(context, suggested)
            tmp.copyTo(dest, overwrite = false)
            DebugLog.step("cloud.save", "apk path=${dest.absolutePath} bytes=${dest.length()}")
            return dest
        }
        ZipFile(tmp).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk") }
            val pick = entries.firstOrNull { it.name.endsWith("app-debug.apk") } ?: entries.firstOrNull()
                ?: error("Cloud Run returned a zip with no APK")
            val dest = ApkLibrary.place(context, File(pick.name).name)
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
