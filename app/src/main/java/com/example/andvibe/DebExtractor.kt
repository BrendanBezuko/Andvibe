package com.example.andvibe

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/** Extract Debian packages (Termux .deb) into a directory. */
object DebExtractor {
    fun extract(deb: File, dest: File) {
        dest.mkdirs()
        ArArchiveInputStream(BufferedInputStream(FileInputStream(deb))).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                val name = entry.name.trimEnd('/')
                if (!name.startsWith("data.tar")) {
                    skipFully(ar, entry.size)
                    continue
                }
                val bytes = readFully(ar, entry.size)
                untar(bytes, name, dest)
            }
        }
    }

    private fun untar(payload: ByteArray, name: String, dest: File) {
        val raw = when {
            name.endsWith(".xz") -> XZCompressorInputStream(ByteArrayInputStream(payload))
            name.endsWith(".gz") -> GZIPInputStream(ByteArrayInputStream(payload))
            else -> ByteArrayInputStream(payload)
        }
        TarArchiveInputStream(BufferedInputStream(raw)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                var path = entry.name.removePrefix("./")
                path = path
                    .removePrefix("data/data/com.termux/files/usr/")
                    .removePrefix("data/data/com.termux/files/")
                if (path.isBlank() || path.contains("..")) continue
                val out = File(dest, path)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it) }
                if (entry.mode and 0b001001001 != 0) {
                    out.setExecutable(true, false)
                }
            }
        }
    }

    private fun readFully(input: ArArchiveInputStream, size: Long): ByteArray {
        val out = ByteArrayOutputStream(size.coerceAtMost(32L * 1024 * 1024).toInt().coerceAtLeast(0))
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun skipFully(input: ArArchiveInputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }
}
