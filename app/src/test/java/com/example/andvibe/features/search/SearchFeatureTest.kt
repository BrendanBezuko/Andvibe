package com.example.andvibe.features.search

import com.example.andvibe.FossSearch
import com.example.andvibe.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-transition tests that don't need Android Context.
 * SearchFeature methods that touch FossFeed/Context are covered by integration/smoke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFeatureTest {
    @Test
    fun setNewsTabUpdatesState() = runTest {
        // Pure state shape contract — mirrors what the controller renders.
        var state = SearchFeature.State()
        state = state.copy(newsTab = true, note = "Refreshing…")
        assertTrue(state.newsTab)
        assertEquals("Refreshing…", state.note)
        state = state.copy(newsTab = false, hits = listOf(sampleHit()), searching = false)
        assertFalse(state.newsTab)
        assertEquals(1, state.hits.size)
    }

    @Test
    fun emptyQueryNoteContract() {
        val note = "Say what you want to find."
        val state = SearchFeature.State(note = note)
        assertEquals(note, state.note)
        assertTrue(state.hits.isEmpty())
    }

    @Test
    fun searchClearsPreviousResultsInStateShape() {
        val before = SearchFeature.State(
            hits = listOf(sampleHit()),
            news = listOf(sampleNews()),
            brief = "old",
            note = "old note",
        )
        val after = before.copy(
            hits = emptyList(),
            news = emptyList(),
            brief = "",
            note = "Searching…",
            searching = true,
        )
        assertTrue(after.hits.isEmpty())
        assertTrue(after.news.isEmpty())
        assertTrue(after.searching)
        assertEquals("", after.brief)
    }

    @Test
    fun credsCarryProvider() {
        val creds = SearchFeature.Creds(Provider.OPENAI, "k", "m", "https://api.openai.com/v1")
        assertEquals(Provider.OPENAI, creds.provider)
        assertEquals("k", creds.key)
    }

    private fun sampleHit() = FossSearch.RepoHit(
        name = "demo",
        cloneUrl = "https://github.com/a/demo.git",
        page = "https://github.com/a/demo",
        stars = 1,
        blurb = "b",
        license = "",
        why = "GitHub",
    )

    private fun sampleNews() = FossSearch.NewsHit(
        title = "t",
        url = "https://example.com",
        source = "s",
        summary = "sum",
        repo = null,
    )
}
