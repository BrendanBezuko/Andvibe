package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PromptStoreTest {
    @Before
    fun setUp() {
        PromptStore.resetForTests()
        PromptStore.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        PromptStore.resetAll()
        PromptStore.resetForTests()
    }

    @Test
    fun everyKindHasNonBlankDefault() {
        for (kind in PromptStore.Kind.entries) {
            assertTrue(kind.name, PromptStore.default(kind).isNotBlank())
            assertEquals(PromptStore.default(kind), PromptStore.get(kind))
        }
    }

    @Test
    fun customSetReset() {
        PromptStore.set(PromptStore.Kind.PLAN, "custom plan prompt")
        assertTrue(PromptStore.isCustom(PromptStore.Kind.PLAN))
        assertEquals("custom plan prompt", PromptStore.get(PromptStore.Kind.PLAN))

        PromptStore.reset(PromptStore.Kind.PLAN)
        assertFalse(PromptStore.isCustom(PromptStore.Kind.PLAN))
        assertEquals(PromptStore.default(PromptStore.Kind.PLAN), PromptStore.get(PromptStore.Kind.PLAN))
    }
}
