package com.example.andvibe.tasks

import com.example.andvibe.Tab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRunnerTest {
    private class Harness(scope: CoroutineScope) {
        val synced = mutableListOf<List<TaskRunner.Task>>()
        var syncThrows = 0
        val notified = mutableListOf<Pair<TaskRunner.Task, TaskRunner.Done>>()
        val runner = TaskRunner(
            scope = scope,
            post = { it.run() },
            serviceSync = {
                if (syncThrows > 0) {
                    syncThrows--
                    throw IllegalStateException("ForegroundServiceStartNotAllowed (fake)")
                }
                synced.add(it)
            },
            notifyDone = { task, done -> notified.add(task to done) },
            onChanged = { },
        )
    }

    private fun TestScope.lane() = StandardTestDispatcher(testScheduler)

    @Test
    fun resourceExclusionFailsFastAndReleasesOnCompletion() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        val r = Resource("x")
        val first = h.runner.launch("one", Tab.CONSOLE, setOf(r), on = lane()) {
            gate.await()
            null
        }
        assertNotNull(first)
        assertTrue(h.runner.holds(r))
        assertNull(h.runner.launch("two", Tab.CONSOLE, setOf(r), on = lane()) { null })
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(h.runner.holds(r))
        assertFalse(h.runner.anyActive())
        assertNotNull(h.runner.launch("three", Tab.CONSOLE, setOf(r), on = lane()) { null })
        advanceUntilIdle()
    }

    @Test
    fun manualClaimIsAtomicAndBlocksLaunch() = runTest {
        val h = Harness(this)
        val r = Resource("build")
        assertTrue(h.runner.tryClaim(r))
        assertFalse(h.runner.tryClaim(r))
        assertNull(h.runner.launch("b", Tab.BUILD, setOf(r), on = lane()) { null })
        h.runner.release(r)
        assertNotNull(h.runner.launch("b", Tab.BUILD, setOf(r), on = lane()) { null })
        advanceUntilIdle()
    }

    @Test
    fun cancelRemovesTaskAndSkipsNotification() = runTest {
        val h = Harness(this)
        val never = CompletableDeferred<Unit>()
        val task = h.runner.launch("long", Tab.VIBE, setOf(Res.AGENT), on = lane()) {
            never.await()
            TaskRunner.Done("finished", "should not appear")
        }
        assertNotNull(task)
        advanceUntilIdle()
        assertTrue(h.runner.anyActive())
        task!!.cancel()
        advanceUntilIdle()
        assertFalse(h.runner.anyActive())
        assertFalse(h.runner.holds(Res.AGENT))
        assertTrue(h.notified.isEmpty())
    }

    @Test
    fun notifiesOnlyWhenInvisibleAndTracked() = runTest {
        val h = Harness(this)
        h.runner.visible = true
        h.runner.launch("a", Tab.BUILD, on = lane()) { TaskRunner.Done("t", "x") }
        advanceUntilIdle()
        assertTrue(h.notified.isEmpty())

        h.runner.visible = false
        h.runner.launch("b", Tab.BUILD, on = lane()) { TaskRunner.Done("done", "y") }
        advanceUntilIdle()
        assertEquals(listOf("done"), h.notified.map { it.second.title })

        h.runner.launch("quiet", Tab.GIT, on = lane(), track = false) { TaskRunner.Done("no", "z") }
        advanceUntilIdle()
        assertEquals(1, h.notified.size)
    }

    @Test
    fun untrackedTasksStayOutOfServiceList() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.runner.launch("bg", Tab.SEARCH, on = lane(), track = false) {
            gate.await()
            null
        }
        assertTrue(h.runner.anyActive())
        assertTrue(h.runner.tracked().isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun serviceSyncFailureDegradesAndResyncRetries() = runTest {
        val h = Harness(this)
        h.syncThrows = 1
        val gate = CompletableDeferred<Unit>()
        val task = h.runner.launch("work", Tab.BUILD, on = lane()) {
            gate.await()
            TaskRunner.Done("ok", "survived")
        }
        assertNotNull(task)
        // The first sync threw, but the task must keep running.
        assertTrue(h.runner.anyActive())
        assertTrue(h.synced.isEmpty())
        // Returning to the foreground retries promotion.
        h.runner.resync()
        assertEquals(1, h.synced.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(h.runner.anyActive())
        assertEquals("ok", h.notified.single().second.title)
    }
}
