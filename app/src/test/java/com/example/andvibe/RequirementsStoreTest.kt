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
class RequirementsStoreTest {
    @Before
    fun setUp() {
        RequirementsStore.resetForTests()
        RequirementsStore.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        RequirementsStore.forget("ws-test")
        RequirementsStore.resetForTests()
    }

    @Test
    fun writeReadForget() {
        RequirementsStore.write("# Need\n\n- one", "ws-test")
        assertEquals("# Need\n\n- one", RequirementsStore.read("ws-test"))
        assertTrue(RequirementsStore.file("ws-test").isFile)

        RequirementsStore.forget("ws-test")
        assertEquals("", RequirementsStore.read("ws-test"))
        assertFalse(RequirementsStore.file("ws-test").isFile)
    }

    @Test
    fun truncatesAtEightyThousandChars() {
        val huge = "x".repeat(90_000)
        RequirementsStore.write(huge, "ws-test")
        assertEquals(80_000, RequirementsStore.read("ws-test").length)
    }
}
