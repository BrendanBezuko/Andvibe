package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectMentionsTest {
    @Test
    fun atQueryFindsPartial() {
        val q = ProjectMentions.atQuery("use @Sub", 8)
        assertEquals(4, q?.start)
        assertEquals("Sub", q?.query)
    }

    @Test
    fun atQueryIgnoresEmail() {
        assertNull(ProjectMentions.atQuery("a@b.com", 3))
    }

    @Test
    fun namesInKeepsKnownOnly() {
        val names = ProjectMentions.namesIn(
            "mirror @Andvibe-subscription into @missing and @Andvibe",
            listOf("Andvibe", "Andvibe-subscription")
        )
        assertEquals(listOf("Andvibe-subscription", "Andvibe"), names)
    }
}
