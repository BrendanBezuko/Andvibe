package com.example.andvibe

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

object Jobs {
    class Job(val id: Int, val label: String, val tab: AppState.Tab, val started: Long)

    private val active = LinkedHashMap<Int, Job>()
    private var nextId = 1
    private val main = Handler(Looper.getMainLooper())

    @Volatile var visible = false

    // Call on the main thread while the app is in front. Android refuses to start a
    // foreground service from the background.
    fun begin(context: Context, label: String, tab: AppState.Tab): Job {
        val job = synchronized(active) {
            Job(nextId++, label, tab, System.currentTimeMillis()).also { active[it.id] = it }
        }
        DebugLog.step("jobs", "begin ${job.id} $label")
        WorkService.sync(context.applicationContext)
        return job
    }

    fun end(job: Job, title: String, text: String, apk: File? = null) {
        val removed = synchronized(active) { active.remove(job.id) } ?: return
        DebugLog.step("jobs", "end ${removed.id} $title")
        val context = AppState.appContext
        main.post { WorkService.sync(context) }
        if (!visible) Notify.done(context, removed, title, text, apk)
    }

    fun active(): List<Job> = synchronized(active) { active.values.toList() }
}
