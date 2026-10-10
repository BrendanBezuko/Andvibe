package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ProjectStoreTest {
    private lateinit var ctx: android.content.Context
    private lateinit var repos: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        repos = File(ctx.filesDir, "repos").also { it.mkdirs() }
        ctx.getSharedPreferences("andvibe_projects", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun rememberAndRestoreUnderRepos() {
        val proj = File(repos, "app").also { it.mkdirs() }
        ProjectStore.remember(ctx, proj)
        assertEquals(proj.canonicalFile, ProjectStore.restore(ctx, repos)?.canonicalFile)
    }

    @Test
    fun rememberIgnoresPathsOutsideRepos() {
        val outside = File(ctx.cacheDir, "sneaky").also { it.mkdirs() }
        ProjectStore.remember(ctx, outside)
        assertNull(ProjectStore.restore(ctx, repos))
    }

    @Test
    fun rememberIgnoresReposRootItself() {
        ProjectStore.remember(ctx, repos)
        assertNull(ProjectStore.restore(ctx, repos))
    }
}
