package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val repos: File by lazy { tmp.newFolder("repos") }

    @Test
    fun relAndDisplay() {
        val child = File(repos, "proj/src/a.txt")
        assertEquals("proj/src/a.txt", RepoFiles.rel(child, repos))
        assertEquals("", RepoFiles.rel(repos, repos))
        assertEquals("~", RepoFiles.display(repos, repos))
        assertEquals("~/proj", RepoFiles.display(File(repos, "proj"), repos))
    }

    @Test
    fun relOfOutsideFileFallsBackToName() {
        val outside = tmp.newFile("outside.txt")
        assertEquals("outside.txt", RepoFiles.rel(outside, repos))
    }

    @Test
    fun resolveStaysInsideRepos() {
        val proj = File(repos, "proj").apply { mkdirs() }
        assertEquals(repos.canonicalFile, RepoFiles.resolve(proj, repos, "~"))
        assertEquals(File(repos, "proj").canonicalFile, RepoFiles.resolve(repos, repos, "~/proj"))
        assertEquals(File(repos, "proj").canonicalFile, RepoFiles.resolve(repos, repos, "/proj"))
        assertEquals(File(proj, "src").canonicalFile, RepoFiles.resolve(proj, repos, "src"))
    }

    @Test
    fun resolveRejectsEscapingRepos() {
        assertThrows(IllegalStateException::class.java) {
            RepoFiles.resolve(repos, repos, "..")
        }
        assertThrows(IllegalStateException::class.java) {
            RepoFiles.resolve(File(repos, "proj"), repos, "../../../etc")
        }
    }

    @Test
    fun safeChildRejectsTraversalAndGit() {
        val root = tmp.newFolder("root")
        assertThrows(IllegalStateException::class.java) { RepoFiles.safeChild(root, "../evil.txt") }
        assertThrows(IllegalStateException::class.java) { RepoFiles.safeChild(root, "/abs.txt") }
        assertThrows(IllegalStateException::class.java) { RepoFiles.safeChild(root, "a/../b.txt") }
        assertThrows(IllegalStateException::class.java) { RepoFiles.safeChild(root, ".git/config") }
        assertThrows(IllegalStateException::class.java) { RepoFiles.safeChild(root, "") }
    }

    @Test
    fun safeChildNormalizesBackslashesAndCreatesParents() {
        val root = tmp.newFolder("root2")
        val child = RepoFiles.safeChild(root, "src\\nested\\file.txt")
        assertEquals(File(root, "src/nested/file.txt").canonicalFile, child)
        assertTrue(child.parentFile!!.isDirectory)
    }

    @Test
    fun walkSkipsIgnoredDirsAndVisitsFiles() {
        val root = tmp.newFolder("walkroot")
        File(root, "a.txt").writeText("a")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("b")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/dep.js").writeText("x")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("x")

        val seen = mutableListOf<String>()
        RepoFiles.walk(root) { seen.add(RepoFiles.rel(it, root)) }
        assertEquals(setOf("a.txt", "sub/b.txt"), seen.toSet())
    }

    @Test
    fun walkKeepsSourcePackageNamedBuildButSkipsGradleOutput() {
        val root = tmp.newFolder("walkbuild")
        File(root, "features/build").mkdirs()
        File(root, "features/build/BuildFeature.kt").writeText("class BuildFeature")
        File(root, "app/build/outputs/apk").mkdirs()
        File(root, "app/build/outputs/apk/app-debug.apk").writeText("apk")
        File(root, "app/build/intermediates").mkdirs()

        val seen = mutableListOf<String>()
        RepoFiles.walk(root) { seen.add(RepoFiles.rel(it, root)) }
        assertTrue(seen.contains("features/build/BuildFeature.kt"))
        assertFalse(seen.any { it.startsWith("app/build/") })
    }

    @Test
    fun looksBinaryDetectsNullBytes() {
        val text = tmp.newFile("plain.txt").apply { writeText("hello") }
        val binary = tmp.newFile("blob.bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        assertFalse(RepoFiles.looksBinary(text))
        assertTrue(RepoFiles.looksBinary(binary))
    }
}
