package com.example.andvibe

import android.app.Application
import com.example.andvibe.core.GitOps

/**
 * Composition root, constructed once in AndVibeApp.onCreate.
 * Construction order is initialization order — the compiler enforces it.
 */
class AppGraph(app: Application) {
    val secrets = SecretStore(app)
    val session: ProjectSession

    init {
        WorkspaceStore.init(app)
        ChatStore.init(app)
        PromptStore.init(app)
        session = ProjectSession(app)
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
