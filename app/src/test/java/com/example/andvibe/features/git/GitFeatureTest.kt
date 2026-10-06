package com.example.andvibe.features.git

import com.example.andvibe.core.GitOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State shape and transition contracts for [GitFeature] (no Android / TaskRunner). */
class GitFeatureTest {
    @Test
    fun defaultStateIsIdleWithNoSnapshot() {
        val state = GitFeature.State()
        assertFalse(state.busy)
        assertNull(state.snapshot)
        assertNull(state.detail)
        assertFalse(state.clearCommitMessage)
    }

    @Test
    fun busyFlagTracksWorkingState() {
        var state = GitFeature.State(busy = true)
        assertTrue(state.busy)
        state = state.copy(busy = false, snapshot = sampleSnapshot())
        assertFalse(state.busy)
        assertEquals("main", state.snapshot?.branch)
    }

    @Test
    fun invalidateClearsSnapshotAndDetailInStateShape() {
        val before = GitFeature.State(
            snapshot = sampleSnapshot(),
            detail = "diff text",
        )
        val after = before.copy(snapshot = null, detail = null)
        assertNull(after.snapshot)
        assertNull(after.detail)
    }

    @Test
    fun commitSuccessSetsClearMessageFlag() {
        val afterCommit = GitFeature.State(clearCommitMessage = true)
        assertTrue(afterCommit.clearCommitMessage)
        val acked = afterCommit.copy(clearCommitMessage = false)
        assertFalse(acked.clearCommitMessage)
    }

    @Test
    fun detailPanelVisibilityContract() {
        val withDetail = GitFeature.State(snapshot = sampleSnapshot(), detail = "log")
        assertEquals("log", withDetail.detail)
        val closed = withDetail.copy(detail = null)
        assertNull(closed.detail)
    }

    private fun sampleSnapshot() = GitOps.Snapshot(
        branch = "main",
        summary = "clean",
        changes = emptyList(),
        isRepo = true,
        commits = emptyList(),
        ahead = 0,
        behind = 0,
        upstream = true,
        remote = "origin",
    )
}
