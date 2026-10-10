package com.example.andvibe

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class DebExtractorRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun extractUnpacksDataTarGzPayload() {
        val payload = "hello from deb\n".toByteArray()
        val deb = File(tmp.root, "tiny.deb")
        writeDeb(deb, "usr/share/andvibe/hello.txt", payload)

        val dest = tmp.newFolder("out")
        DebExtractor.extract(deb, dest)

        val out = File(dest, "usr/share/andvibe/hello.txt")
        assertTrue(out.isFile)
        assertEquals("hello from deb\n", out.readText())
    }

    @Test
    fun extractStripsTermuxUsrPrefix() {
        val payload = ByteArray(8) { 0x42 }
        val deb = File(tmp.root, "termux.deb")
        writeDeb(
            deb,
            "data/data/com.termux/files/usr/lib/libdemo.so",
            payload,
        )

        val dest = tmp.newFolder("termux-out")
        DebExtractor.extract(deb, dest)

        val out = File(dest, "lib/libdemo.so")
        assertTrue(out.isFile)
        assertEquals(payload.toList(), out.readBytes().toList())
    }

    private fun writeDeb(deb: File, memberPath: String, content: ByteArray) {
        val dataTarGz = gzipTar(memberPath, content)
        ArArchiveOutputStream(FileOutputStream(deb)).use { ar ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ArArchiveEntry(name, bytes.size.toLong())
                ar.putArchiveEntry(entry)
                ar.write(bytes)
                ar.closeArchiveEntry()
            }
            put("debian-binary", "2.0\n".toByteArray())
            put("control.tar.gz", gzipTar("./control", "Package: tiny\n".toByteArray()))
            put("data.tar.gz", dataTarGz)
        }
    }

    private fun gzipTar(path: String, content: ByteArray): ByteArray {
        val tarBytes = ByteArrayOutputStream()
        TarArchiveOutputStream(tarBytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            val entry = TarArchiveEntry(path)
            entry.size = content.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(content)
            tar.closeArchiveEntry()
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tarBytes.toByteArray()) }
        return gz.toByteArray()
    }
}
