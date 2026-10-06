package com.example.andvibe.tasks

import com.example.andvibe.DebugLog
import com.example.andvibe.Tab
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@JvmInline
value class Resource(val key: String)

/** App-wide resource tokens. Per-repo keys arrive with multi-repo support. */
object Res {
    val AGENT = Resource("agent")
    val BUILD = Resource("build")
    val REVISE = Resource("revise")
    val UNDERSTAND = Resource("understand")
    val GIT = Resource("git")
    val CONSOLE = Resource("console")
    val FIND = Resource("find")
    val FEED = Resource("feed")
    val DOWNLOAD = Resource("download")
    val IMPORT = Resource("import")
}

/**
 * The one job system (DESIGN.md §3.3): tracks running work, enforces resource
 * exclusion, and drives the foreground service, busy UI, and done notifications.
 * A scheduler, not a domain object — it does not know what "build" means.
 * Replaces Jobs and the old per-flag busy booleans.
 */
class TaskRunner(
    private val scope: CoroutineScope,
    private val post: (Runnable) -> Unit,
    private val serviceSync: (List<Task>) -> Unit,
    private val notifyDone: (Task, Done) -> Unit,
    private val onChanged: () -> Unit,
) {
    class Task internal constructor(
        val id: Int,
        val label: String,
        val tab: Tab,
        val started: Long,
        val holds: Set<Resource>,
        /** Tracked tasks show in the foreground service and done notifications. */
        val track: Boolean,
    ) {
        internal var job: Job? = null

        /** Marks the task cancelled. Blocking work notices at its next stop-flag check. */
        fun cancel() {
            job?.cancel()
        }
    }

    class Done(val title: String, val text: String, val apk: File? = null)

    @Volatile var visible = false

    private val lock = Any()
    private val active = LinkedHashMap<Int, Task>()
    private val claimed = mutableSetOf<Resource>()
    private var nextId = 1

    fun tracked(): List<Task> = synchronized(lock) { active.values.filter { it.track } }
    fun newest(): Task? = synchronized(lock) { active.values.lastOrNull() }
    fun anyActive(): Boolean = synchronized(lock) { active.isNotEmpty() }
    fun holds(resource: Resource): Boolean = synchronized(lock) { resource in claimed }

    /** Atomic claim for work nested inside another task (agent tools). */
    fun tryClaim(resource: Resource): Boolean {
        val ok = synchronized(lock) { claimed.add(resource) }
        if (ok) changed()
        return ok
    }

    fun release(resource: Resource) {
        synchronized(lock) { claimed.remove(resource) }
        changed()
    }

    /** Re-sync the service and busy UI, e.g. after returning to the foreground. */
    fun resync() = changed()

    /**
     * Call on the main thread while the app is in the foreground — Android refuses
     * to start a foreground service from the background. Returns null when a
     * required resource is already held (fail fast, no queueing).
     */
    fun launch(
        label: String,
        tab: Tab,
        holds: Set<Resource> = emptySet(),
        on: CoroutineContext,
        track: Boolean = true,
        block: suspend () -> Done?,
    ): Task? {
        val task = synchronized(lock) {
            if (holds.any { it in claimed }) return null
            claimed.addAll(holds)
            Task(nextId++, label, tab, System.currentTimeMillis(), holds, track)
                .also { active[it.id] = it }
        }
        DebugLog.step("tasks", "begin ${task.id} $label")
        changed()
        task.job = scope.launch {
            var done: Done? = null
            try {
                done = withContext(on) { block() }
            } catch (t: CancellationException) {
                DebugLog.step("tasks", "cancelled ${task.id} $label")
            } catch (t: Throwable) {
                DebugLog.step("tasks", "error ${task.id} ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                synchronized(lock) {
                    active.remove(task.id)
                    claimed.removeAll(task.holds)
                }
                DebugLog.step("tasks", "end ${task.id} ${done?.title ?: label}")
                changed()
                if (done != null && task.track && !visible) notifyDone(task, done)
            }
        }
        return task
    }

    private fun changed() {
        post(
            Runnable {
                runCatching { serviceSync(tracked()) }
                    .onFailure { DebugLog.step("tasks", "sync failed ${it.javaClass.simpleName}: ${it.message}") }
                onChanged()
            }
        )
    }
}
