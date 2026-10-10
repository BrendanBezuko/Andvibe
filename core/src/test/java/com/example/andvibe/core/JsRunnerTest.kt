package com.example.andvibe.core

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun compileReportsSyntaxError() {
        val root = tmp.newFolder("proj")
        File(root, "bad.js").writeText("function ({\n")
        val out = JsRunner.compile(root)
        assertTrue(out.contains("bad.js") || out.contains("error") || out.contains("Syntax"))
    }

    @Test
    fun testRunsPassingAssert() {
        val root = tmp.newFolder("proj")
        File(root, "math.test.js").writeText("assert.equal(1 + 2, 3);\n")
        val out = JsRunner.test(root)
        assertTrue(out, out.contains("TOTAL pass 1 fail 0"))
    }
}
