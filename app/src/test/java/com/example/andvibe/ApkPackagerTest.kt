package com.example.andvibe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPackagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun collectKeepsWebFilesOnly() {
        val repo = tmp.newFolder("myrepo")
        File(repo, "index.html").writeText("<html></html>")
        File(repo, "app.js").writeText("console.log(1)")
        File(repo, "tool.exe").writeBytes(byteArrayOf(1, 2, 3))
        File(repo, "node_modules").mkdirs()
        File(repo, "node_modules/dep.js").writeText("skipped")

        val assets = ApkPackager.collect(repo) { }
        assertEquals(setOf("index.html", "app.js"), assets.map { it.path }.toSet())
    }

    @Test
    fun collectSkipsOversizeFilesAndLogsIt() {
        val repo = tmp.newFolder("bigrepo")
        File(repo, "index.html").writeText("<html></html>")
        RandomAccessFile(File(repo, "huge.css"), "rw").use { it.setLength(5 * 1024 * 1024 + 1L) }

        val logged = mutableListOf<String>()
        val assets = ApkPackager.collect(repo) { logged.add(it) }
        assertEquals(listOf("index.html"), assets.map { it.path })
        assertTrue(logged.any { it.contains("skipped 1") })
    }

    @Test
    fun collectGeneratesIndexWhenMissing() {
        val repo = tmp.newFolder("jsonly")
        File(repo, "main.js").writeText("console.log('hi')")

        val assets = ApkPackager.collect(repo) { }
        val index = assets.single { it.path == "index.html" }
        assertNull(index.file)
        val html = String(index.bytes!!, Charsets.UTF_8)
        assertTrue(html.contains("<script src=\"main.js\"></script>"))
        assertTrue(html.contains("<title>jsonly</title>"))
    }

    @Test
    fun collectAliasesCaseVariantIndex() {
        val repo = tmp.newFolder("cased")
        val original = File(repo, "Index.html")
        original.writeText("<html>real</html>")

        val assets = ApkPackager.collect(repo) { }
        val alias = assets.single { it.path == "index.html" }
        assertEquals(original.canonicalFile, alias.file?.canonicalFile)
        assertTrue(assets.any { it.path == "Index.html" })
    }

    @Test
    fun collectWarnsAboutGradleProjects() {
        val repo = tmp.newFolder("gradleproj")
        File(repo, "index.html").writeText("<html></html>")
        File(repo, "build.gradle").writeText("")

        val logged = mutableListOf<String>()
        ApkPackager.collect(repo) { logged.add(it) }
        assertTrue(logged.any { it.contains("Gradle project") })
    }

    @Test
    fun repackDropsSignatureAndStaleAssetsAndKeepsArscStored() {
        val arsc = ByteArray(100) { (it * 7).toByte() }
        val dex = "dex bytes ".repeat(50).toByteArray()
        val template = tmp.newFile("template.apk")
        ZipOutputStream(template.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex)
            zip.closeEntry()
            zip.putNextEntry(storedEntry("resources.arsc", arsc))
            zip.write(arsc)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("old signature".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/www/stale.html"))
            zip.write("stale".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/"))
            zip.closeEntry()
        }

        val fresh = "<html>new</html>".toByteArray()
        val dest = tmp.newFile("unsigned.apk")
        ApkPackager.writeUnsigned(
            template,
            dest,
            listOf(ApkPackager.Asset("index.html", null, fresh))
        )

        ZipFile(dest).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("classes.dex", "resources.arsc", "assets/www/index.html"), names)
            val repacked = zip.getEntry("resources.arsc")
            assertEquals(ZipEntry.STORED, repacked.method)
            assertArrayEquals(arsc, zip.getInputStream(repacked).use { it.readBytes() })
            assertArrayEquals(dex, zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() })
            assertArrayEquals(fresh, zip.getInputStream(zip.getEntry("assets/www/index.html")).use { it.readBytes() })
        }
    }

    @Test
    fun safeNameStripsUnsafeCharacters() {
        assertEquals("MyApp", ApkPackager.safeName("My App!"))
        assertEquals("foo-bar_1.2", ApkPackager.safeName("foo-bar_1.2"))
        assertEquals("app", ApkPackager.safeName("###"))
        assertEquals("app", ApkPackager.safeName(""))
    }

    private fun storedEntry(name: String, data: ByteArray): ZipEntry =
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
}
