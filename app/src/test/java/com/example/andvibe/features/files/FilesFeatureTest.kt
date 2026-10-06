package com.example.andvibe.features.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesFeatureTest {
    @Test
    fun defaultStateIsIdle() {
        val state = FilesFeature.State()
        assertTrue(state.entries.isEmpty())
        assertNull(state.openFile)
        assertNull(state.pathBanner)
        assertFalse(state.importBusy)
        assertFalse(state.downloadBusy)
    }

    @Test
    fun pathBannerSurfacesWhileImporting() {
        val state = FilesFeature.State(pathBanner = "Importing folder…", importBusy = true)
        assertEqualsBanner(state)
    }

    private fun assertEqualsBanner(state: FilesFeature.State) {
        assertTrue(state.importBusy)
        assertTrue(state.pathBanner!!.startsWith("Importing"))
    }
}
