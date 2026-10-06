package com.example.andvibe.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ZipWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeZip(block: (ZipWriter) -> Unit): File {
        val file = tmp.newFile()
        file.outputStream().use { out ->
            val writer = ZipWriter(out)
            block(writer)
            writer.finish()
        }
        return file
    }

    @Test
    fun roundTripPreservesContentCrcAndMethod() {
        val text = "hello world ".repeat(500).toByteArray()
        val binary = ByteArray(1000) { (it % 251).toByte() }
        val file = writeZip { zip ->
            zip.add("docs/readme.txt", text, ZipEntry.DEFLATED)
            zip.add("resources.arsc", binary, ZipEntry.STORED)
            zip.add("empty.txt", ByteArray(0), ZipEntry.DEFLATED)
        }
        ZipFile(file).use { zip ->
            assertEquals(3, zip.size())

            val readme = zip.getEntry("docs/readme.txt")
            assertEquals(ZipEntry.DEFLATED, readme.method)
            assertEquals(text.size.toLong(), readme.size)
            assertEquals(CRC32().apply { update(text) }.value, readme.crc)
            assertArrayEquals(text, zip.getInputStream(readme).use { it.readBytes() })

            val arsc = zip.getEntry("resources.arsc")
            assertEquals(ZipEntry.STORED, arsc.method)
            assertArrayEquals(binary, zip.getInputStream(arsc).use { it.readBytes() })

            val empty = zip.getEntry("empty.txt")
            assertEquals(ZipEntry.STORED, empty.method)
            assertEquals(0L, empty.size)
        }
    }

    @Test
    fun storedEntryDataIsFourByteAligned() {
        // Odd-length names and payloads force non-trivial padding.
        val file = writeZip { zip ->
            zip.add("a", ByteArray(3) { 1 }, ZipEntry.DEFLATED)
            zip.add("stored1", ByteArray(10) { 2 }, ZipEntry.STORED)
            zip.add("bc", ByteArray(7) { 3 }, ZipEntry.DEFLATED)
            zip.add("resources.arsc", ByteArray(33) { 4 }, ZipEntry.STORED)
        }
        val offsets = storedDataOffsets(file.readBytes())
        assertEquals(listOf("stored1", "resources.arsc"), offsets.map { it.first })
        for ((name, dataOffset) in offsets) {
            assertEquals("entry $name is not 4-byte aligned", 0L, dataOffset % 4)
        }
    }

    @Test
    fun utf8NamesSurvive() {
        val name = "assets/www/héllo-世界.txt"
        val file = writeZip { zip -> zip.add(name, "x".toByteArray(), ZipEntry.DEFLATED) }
        ZipFile(file, Charsets.UTF_8).use { zip ->
            assertEquals(name, zip.entries().nextElement().name)
        }
    }

    @Test
    fun rejectsDuplicateNames() {
        tmp.newFile().outputStream().use { out ->
            val zip = ZipWriter(out)
            zip.add("a.txt", byteArrayOf(1), ZipEntry.DEFLATED)
            assertThrows(IllegalArgumentException::class.java) {
                zip.add("a.txt", byteArrayOf(2), ZipEntry.DEFLATED)
            }
        }
    }

    @Test
    fun rejectsDirectoryAndEmptyNames() {
        tmp.newFile().outputStream().use { out ->
            val zip = ZipWriter(out)
            assertThrows(IllegalArgumentException::class.java) {
                zip.add("dir/", ByteArray(0), ZipEntry.STORED)
            }
            assertThrows(IllegalArgumentException::class.java) {
                zip.add("", byteArrayOf(1), ZipEntry.DEFLATED)
            }
        }
    }

    @Test
    fun rejectsUseAfterFinish() {
        tmp.newFile().outputStream().use { out ->
            val zip = ZipWriter(out)
            zip.finish()
            assertThrows(IllegalStateException::class.java) {
                zip.add("a.txt", byteArrayOf(1), ZipEntry.DEFLATED)
            }
            assertThrows(IllegalStateException::class.java) { zip.finish() }
        }
    }

    @Test
    fun emptyArchiveIsReadable() {
        val file = writeZip { }
        ZipFile(file).use { zip -> assertEquals(0, zip.size()) }
    }

    @Test
    fun archiveHasNoTrailingGarbage() {
        val file = writeZip { zip -> zip.add("a.txt", "hi".toByteArray(), ZipEntry.DEFLATED) }
        val bytes = file.readBytes()
        // End-of-central-directory record is 22 bytes with an empty comment and must close the file.
        val eocd = bytes.size - 22
        assertTrue(eocd > 0)
        assertEquals(0x06054b50, readInt(bytes, eocd))
    }

    /** Walks local file headers in order; returns (name, absolute data offset) for stored entries. */
    private fun storedDataOffsets(bytes: ByteArray): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        var cursor = 0
        while (cursor + 4 <= bytes.size && readInt(bytes, cursor) == 0x04034b50) {
            val method = readShort(bytes, cursor + 8)
            val compressed = readInt(bytes, cursor + 18)
            val nameLen = readShort(bytes, cursor + 26)
            val extraLen = readShort(bytes, cursor + 28)
            val name = String(bytes, cursor + 30, nameLen, Charsets.UTF_8)
            val dataOffset = cursor + 30 + nameLen + extraLen
            if (method == ZipEntry.STORED) out.add(name to dataOffset.toLong())
            cursor = dataOffset + compressed
        }
        return out
    }

    private fun readShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(b: ByteArray, at: Int): Int =
        readShort(b, at) or (readShort(b, at + 2) shl 16)
}
