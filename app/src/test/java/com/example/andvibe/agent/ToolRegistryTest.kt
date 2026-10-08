package com.example.andvibe.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ctx(root: File = tmp.root): AgentContext = AgentContext(root, tmp.root)

    private fun registry(
        build: (File) -> String = { error("build not expected") },
        include: (String) -> Unit = {},
    ) = ToolRegistry(build = build, includeProject = include)

    @Test
    fun targetRejectsDotDot() {
        try {
            ToolRegistry.target(tmp.root, "../outside")
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun writableRejectsGitDir() {
        File(tmp.root, ".git").mkdirs()
        File(tmp.root, ".git/config").writeText("x")
        try {
            ToolRegistry.writable(tmp.root, ".git/config")
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(".git"))
        }
    }

    @Test
    fun globRegexMatchesNestedKt() {
        val re = ToolRegistry.globRegex("**/*.kt")
        assertTrue(re.matches("src/main/Foo.kt"))
        assertTrue(!re.matches("Foo.java"))
    }

    @Test
    fun editAndReadRoundTrip() {
        val root = tmp.root
        File(root, "a.txt").writeText("hello world\n")
        val reg = registry()
        val c = ctx(root)
        val edited = reg.run(
            "edit_file",
            JSONObject()
                .put("path", "a.txt")
                .put("old_string", "hello")
                .put("new_string", "hi"),
            c,
        )
        assertTrue(edited.contains("edited"))
        val read = reg.run("read_file", JSONObject().put("path", "a.txt"), c)
        assertTrue(read.contains("hi world"))
        assertEquals(1, c.changed.size)
    }

    @Test
    fun writeFileCreatesAndReplace() {
        val reg = registry()
        val c = ctx()
        val created = reg.run(
            "write_file",
            JSONObject().put("path", "n.txt").put("content", "one\n"),
            c,
        )
        assertTrue(created.startsWith("created"))
        val replaced = reg.run(
            "write_file",
            JSONObject().put("path", "n.txt").put("content", "two\n"),
            c,
        )
        assertTrue(replaced.startsWith("replaced"))
        assertEquals("two\n", File(tmp.root, "n.txt").readText())
    }

    @Test
    fun buildDelegatesToInjectedCallback() {
        var seen: File? = null
        val reg = registry(build = { root ->
            seen = root
            "BUILD SUCCESSFUL\nAPK: app.apk"
        })
        val out = reg.run("build", JSONObject(), ctx())
        assertEquals(tmp.root.canonicalFile, seen?.canonicalFile)
        assertTrue(out.contains("BUILD SUCCESSFUL"))
        val alias = reg.run("cloud_build", JSONObject(), ctx())
        assertTrue(alias.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun planSpecsAreReadOnlySubset() {
        val names = registry().planSpecs.map { it.name }.toSet()
        assertEquals(setOf("list_dir", "read_file", "grep", "git_status", "git_diff"), names)
        assertTrue("build" !in names)
        assertTrue("cloud_build" !in names)
        assertTrue("edit_file" !in names)
    }
}
