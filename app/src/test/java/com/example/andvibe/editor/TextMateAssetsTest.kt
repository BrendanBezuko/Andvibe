package com.example.andvibe.editor

import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import android.content.Context
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Loads every bundled grammar and language config through Sora's real TextMate stack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TextMateAssetsTest {
    private val samples = mapOf(
        "source.kotlin" to "fun main() { val x = \"hi\" // c",
        "source.java" to "public class A { int x = 1; }",
        "source.js" to "const a = `t${'$'}{b}`; // c",
        "source.ts" to "let n: number = 1",
        "source.json" to "{\"a\": [1, true]}",
        "source.python" to "def f(x): return x  # c",
        "source.yaml" to "key: value # c",
        "text.xml" to "<a b=\"c\"/>",
        "text.html.basic" to "<div><script>let a = 1</script></div>",
        "text.html.markdown" to "# Title `code`",
    )

    @Test
    fun everyLanguageLoadsAndTokenizes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(ctx.assets))
        val registry = GrammarRegistry.getInstance()
        registry.loadGrammars("textmate/languages.json")
        val langs = JSONObject(ctx.assets.open("textmate/languages.json").bufferedReader().readText()).getJSONArray("languages")
        assertTrue(langs.length() >= 20)
        for (i in 0 until langs.length()) {
            val lang = langs.getJSONObject(i)
            val scope = lang.getString("scopeName")
            val grammar = registry.findGrammar(scope)
            assertNotNull("grammar $scope did not load", grammar)
            val line = samples[scope] ?: "x = 1"
            val tokens = grammar!!.tokenizeLine(line, null, null).tokens
            assertTrue("no tokens for $scope", tokens.isNotEmpty())
            if (scope in samples) {
                assertTrue("only root scope for $scope", tokens.any { it.scopes.size > 1 })
            }
            val config = lang.optString("languageConfiguration")
            if (config.isNotEmpty()) {
                val parsed = LanguageConfiguration.load(ctx.assets.open(config).reader())
                assertNotNull("config $config did not parse", parsed)
                assertNotNull("config $config has no brackets", parsed!!.brackets)
            }
        }
    }
}
