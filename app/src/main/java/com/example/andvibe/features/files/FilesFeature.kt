package com.example.andvibe.features.files

import android.content.Context
import android.net.Uri
import com.example.andvibe.DebugLog
import com.example.andvibe.ProjectSession
import com.example.andvibe.ProjectStore
import com.example.andvibe.Tab
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FilesFeature(
    private val app: Context,
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val session: ProjectSession,
    private val log: (String) -> Unit,
    private val onGitInvalidate: () -> Unit = {},
    private val onFilesChanged: () -> Unit,
    private val onProjectChanged: () -> Unit,
    /** Import a document tree under [repos]; returns the created project directory. */
    private val importFolder: (uri: Uri, repos: File, note: (String) -> Unit) -> File,
) {
    data class State(
        val cwd: File = File("."),
        val entries: List<File> = emptyList(),
        val openFile: File? = null,
        val pathBanner: String? = null,
        val importBusy: Boolean = false,
        val downloadBusy: Boolean = false,
    )

    sealed interface Effect {
        data class OpenEditor(val file: File) : Effect
        data class ShowProjects(val dirs: List<File>) : Effect
        data object PickDocumentTree : Effect
        data class ShowImportError(val message: String) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun refresh() {
        val cwd = session.cwd
        val repos = session.reposDir
        val atRoot = cwd.canonicalPath == repos.canonicalPath
        val files = cwd.listFiles()
            ?.filter { it.name != ".git" && (!atRoot || WorkspaceStore.contains(it)) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        _state.update {
            it.copy(
                cwd = cwd,
                entries = files,
                openFile = session.openFile,
                importBusy = tasks.holds(Res.IMPORT),
                downloadBusy = tasks.holds(Res.DOWNLOAD),
            )
        }
    }

    fun setPathBanner(note: String?) {
        _state.update { it.copy(pathBanner = note) }
    }

    fun openEntry(file: File) {
        if (file.isDirectory) {
            session.cwd = file
            refresh()
            onFilesChanged()
        } else {
            effects.tryEmit(Effect.OpenEditor(file))
        }
    }

    fun up() {
        val parent = session.cwd.parentFile ?: return
        if (!parent.canonicalPath.startsWith(session.reposDir.canonicalPath)) return
        session.cwd = parent
        refresh()
        onFilesChanged()
    }

    fun requestPickFolder() {
        if (tasks.holds(Res.IMPORT) || tasks.holds(Res.DOWNLOAD)) return
        effects.tryEmit(Effect.PickDocumentTree)
    }

    fun requestProjects() {
        effects.tryEmit(Effect.ShowProjects(WorkspaceStore.activeRepos()))
    }

    fun openProject(dir: File) {
        session.cwd = dir.canonicalFile
        session.openFile = null
        ProjectStore.remember(app, session.cwd)
        onGitInvalidate()
        onProjectChanged()
        refresh()
    }

    fun startImport(uri: Uri) {
        if (tasks.holds(Res.IMPORT) || tasks.holds(Res.DOWNLOAD)) return
        setPathBanner("Importing folder…")
        tasks.launch("Importing folder", Tab.FILES, setOf(Res.IMPORT), dispatchers.repo) {
            var failed: String? = null
            try {
                val dest = importFolder(uri, session.reposDir) { log(it) }
                WorkspaceStore.include(dest.name)
                session.cwd = dest.canonicalFile
                ProjectStore.remember(app, session.cwd)
                session.openFile = null
                onGitInvalidate()
                onProjectChanged()
            } catch (t: Throwable) {
                failed = t.message ?: t.javaClass.simpleName
                log("import failed: $failed")
                DebugLog.step("import", "fail $failed")
            } finally {
                setPathBanner(null)
                refresh()
                onFilesChanged()
            }
            if (failed != null) {
                effects.tryEmit(Effect.ShowImportError(failed))
                TaskRunner.Done("Import failed", failed)
            } else {
                TaskRunner.Done("Import finished", RepoFiles.display(session.cwd, session.reposDir))
            }
        }
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                importBusy = tasks.holds(Res.IMPORT),
                downloadBusy = tasks.holds(Res.DOWNLOAD),
            )
        }
    }
}
