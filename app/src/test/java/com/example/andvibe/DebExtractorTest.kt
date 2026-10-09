package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DebExtractorTest {
    @Test
    fun materializeLinksResolvesSonameChain() {
        val root = createTempDirectory("deb-links").toFile()
        try {
            val lib = File(root, "lib").also { it.mkdirs() }
            val real = File(lib, "libz.so.1.3.1").also { it.writeBytes(ByteArray(2048) { 0x7f }) }
            // Simulate tar symlink entries (no payload) waiting to be filled.
            val so1 = File(lib, "libz.so.1")
            val so = File(lib, "libz.so")
            DebExtractor.materializeLinks(
                listOf(
                    so1 to "libz.so.1.3.1",
                    so to "libz.so.1",
                ),
                root,
            )
            assertTrue(so1.isFile)
            assertTrue(so.isFile)
            assertEquals(real.length(), so1.length())
            assertEquals(real.length(), so.length())
            assertEquals(0x7f, so.readBytes()[0].toInt() and 0xff)
        } finally {
            root.deleteRecursively()
        }
    }
}
