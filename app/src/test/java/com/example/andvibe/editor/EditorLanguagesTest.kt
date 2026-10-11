package com.example.andvibe.editor

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLanguagesTest {
    @Test
    fun mapsCommonFiles() {
        assertEquals("source.kotlin", EditorLanguages.scopeFor("MainActivity.kt"))
        assertEquals("source.kotlin", EditorLanguages.scopeFor("build.gradle.kts"))
        assertEquals("source.groovy", EditorLanguages.scopeFor("build.gradle"))
        assertEquals("text.xml", EditorLanguages.scopeFor("AndroidManifest.xml"))
        assertEquals("source.ts", EditorLanguages.scopeFor("src/index.TS"))
        assertEquals("source.json.comments", EditorLanguages.scopeFor("tsconfig.json"))
        assertEquals("source.json", EditorLanguages.scopeFor("package.json"))
        assertEquals("source.ini", EditorLanguages.scopeFor("libs.versions.toml"))
        assertEquals("source.shell", EditorLanguages.scopeFor(".bashrc"))
        assertEquals("text.html.markdown", EditorLanguages.scopeFor("README.md"))
    }

    @Test
    fun plainTextWhenUnknown() {
        assertNull(EditorLanguages.scopeFor("LICENSE"))
        assertNull(EditorLanguages.scopeFor(".gitignore"))
        assertNull(EditorLanguages.scopeFor("Dockerfile"))
        assertNull(EditorLanguages.scopeFor("photo.png"))
        assertEquals("Plain text", EditorLanguages.label(null))
        assertEquals("Kotlin", EditorLanguages.label("source.kotlin"))
    }

    /** Every scope we map a file to must be registered in assets/textmate/languages.json. */
    @Test
    fun everyMappedScopeIsRegistered() {
        val json = JSONObject(File("src/main/assets/textmate/languages.json").readText())
        val langs = json.getJSONArray("languages")
        val registered = (0 until langs.length()).map { langs.getJSONObject(it).getString("scopeName") }.toSet()
        val samples = listOf(
            "a.kt", "a.java", "a.gradle", "a.js", "a.jsx", "a.ts", "a.tsx", "a.json", "a.jsonc", "a.css",
            "a.py", "a.sh", "a.yml", "a.ini", "a.xml", "a.html", "a.md",
        )
        for (name in samples) {
            val scope = EditorLanguages.scopeFor(name)!!
            assertTrue("$name → $scope is not in languages.json", scope in registered)
        }
    }
}
