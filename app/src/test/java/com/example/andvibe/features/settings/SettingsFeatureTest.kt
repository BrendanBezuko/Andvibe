package com.example.andvibe.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsFeatureTest {
    @Test
    fun defaultState() {
        val state = SettingsFeature.State()
        assertEquals("", state.mcpStatus)
        assertFalse(state.plainSecrets)
    }
}
