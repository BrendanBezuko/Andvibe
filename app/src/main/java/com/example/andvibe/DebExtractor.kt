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
        val links = ArrayList<Pair<File, String>>()
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
                if (entry.isSymbolicLink || entry.isLink) {
                    val target = entry.linkName?.takeIf { it.isNotBlank() } ?: continue
                    links.add(out to target)
                    continue
                }
                FileOutputStream(out).use { tar.copyTo(it) }
                if (entry.mode and 0b001001001 != 0) {
                    out.setExecutable(true, false)
                }
            }
        }
        materializeLinks(links, dest)
    }

    /**
     * Tar symlinks have no payload — writing the stream creates a 0-byte file
     * (that was shipping as empty libz.so). Resolve link chains to real bytes.
     */
    internal fun materializeLinks(links: List<Pair<File, String>>, dest: File) {
        if (links.isEmpty()) return
        var passes = links.size + 2
        while (passes-- > 0) {
            var progress = false
            for ((link, target) in links) {
                if (link.isFile && link.length() > 0) continue
                val resolved = resolveLinkTarget(link, target, dest) ?: continue
                if (!resolved.isFile || resolved.length() == 0L) continue
                if (resolved.absolutePath == link.absolutePath) continue
                link.parentFile?.mkdirs()
                resolved.copyTo(link, overwrite = true)
                progress = true
            }
            if (!progress) return
        }
    }

    private fun resolveLinkTarget(link: File, target: String, dest: File): File? {
        val candidates = ArrayList<File>(3)
        val raw = File(target)
        if (raw.isAbsolute) {
            candidates.add(raw)
            // Termux absolute paths still land under our extract dest after prefix strip.
            val trimmed = target
                .removePrefix("/data/data/com.termux/files/usr/")
                .removePrefix("/data/data/com.termux/files/")
                .removePrefix("/")
            if (trimmed.isNotBlank()) candidates.add(File(dest, trimmed))
        } else {
            link.parentFile?.let { candidates.add(File(it, target)) }
            candidates.add(File(dest, target))
        }
        return candidates.map { it.normalize() }.firstOrNull { it.isFile && it.length() > 0 }
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
