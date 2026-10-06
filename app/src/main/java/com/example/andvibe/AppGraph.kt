package com.example.andvibe

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.example.andvibe.core.GitOps
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.TaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root, constructed once in AndVibeApp.onCreate.
 * Construction order is initialization order — the compiler enforces it.
 */
class AppGraph(app: Application) {
    val dispatchers = AppDispatchers()
    val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    val secrets = SecretStore(app)
    val session: ProjectSession
    val tasks: TaskRunner

    init {
        session = ProjectSession(app)
        val main = Handler(Looper.getMainLooper())
        tasks = TaskRunner(
            scope = scope,
            post = { main.post(it) },
            serviceSync = { WorkService.sync(app, it) },
            notifyDone = { task, done -> Notify.done(app, task, done.title, done.text, done.apk) },
            onChanged = { UiBridge.busyUpdate() },
        )
        // Before the stores: ChatStore.init syncs, which reads the derived busy state.
        AppState.tasks = tasks
        WorkspaceStore.init(app)
        ChatStore.init(app)
        PromptStore.init(app)
        GitOps.auth = {
            GitOps.Auth(
                name = secrets.gitName().ifBlank { "AndVibe" },
                email = secrets.gitEmail().ifBlank { "andvibe@local" },
                user = secrets.gitUser(),
                token = secrets.gitToken(),
            )
        }
        AppState.init(app, session)
    }
}
