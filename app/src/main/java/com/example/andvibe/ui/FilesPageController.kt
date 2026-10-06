package com.example.andvibe.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.FileIcons
import com.example.andvibe.ProjectSession
import com.example.andvibe.ProjectStore
import com.example.andvibe.R
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.databinding.PageFilesBinding
import com.example.andvibe.features.files.FilesFeature
import java.io.File
import kotlinx.coroutines.launch

/**
 * Renders the Files tab list/editor and forwards import/project actions to [FilesFeature].
 */
class FilesPageController(
    private val page: PageFilesBinding,
    private val feature: FilesFeature,
    private val session: ProjectSession,
    private val lifecycleOwner: LifecycleOwner,
    private val inflate: LayoutInflater,
    private val density: Float,
    private val launchDocumentTree: () -> Unit,
    private val onOpenWorkspace: () -> Unit,
    private val log: (String) -> Unit,
    private val paintBusy: () -> Unit,
    private val paintTape: () -> Unit,
    private val paintProject: () -> Unit,
    private val syncBack: () -> Unit,
    private val onAfterOpenProject: () -> Unit,
    private val refreshGitIfVisible: () -> Unit,
    private val markVibeProjectChanged: () -> Unit,
) {
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()

    val isEditing: Boolean get() = editing

    fun start() {
        page.filesUp.setOnClickListener { up() }
        page.filesOpen.setOnClickListener { pickFolder() }
        page.filesProjects.setOnClickListener { showProjects() }
        page.filesSave.setOnClickListener { saveEditor(announce = true) }
        page.filesClose.setOnClickListener { closeEditor(save = true) }
        page.fileList.setOnItemClickListener { _, _, position, _ ->
            val file = displayed.getOrNull(position) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                session.cwd = file
                ProjectStore.remember(page.root.context, file)
                refreshFileList()
            } else {
                openEditor(file)
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    feature.state.collect {
                        if (!editing) refreshFileListFromState(it)
                        else paintFilesBusy(it)
                    }
                }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            is FilesFeature.Effect.OpenEditor -> openEditor(effect.file)
                            is FilesFeature.Effect.ShowProjects -> showProjects()
                            FilesFeature.Effect.PickDocumentTree -> pickFolder()
                            is FilesFeature.Effect.ShowImportError ->
                                log("import failed: ${effect.message}")
                        }
                    }
                }
            }
        }
    }

    fun onTabVisible() {
        if (!editing) refreshFileList()
    }

    fun onFilesChanged() {
        if (!editing) {
            refreshFileList()
            return
        }
        val file = session.openFile
        if (file == null || !file.isFile) {
            editing = false
            session.openFile = null
            savedText = ""
            page.editor.setText("")
            refreshFileList()
            return
        }
        if (page.editor.text?.toString() != savedText) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text != savedText) {
            savedText = text
            page.editor.setText(text)
        }
    }

    fun onProjectChanged() {
        saveEditor(announce = false)
        editing = false
        session.openFile = null
        savedText = ""
        page.editor.setText("")
        refreshFileList()
    }

    fun setCloneErrorPath(message: String) {
        if (!editing) page.filesPath.text = message
    }

    fun refreshFileList() {
        feature.refresh()
        refreshFileListFromState(feature.state.value)
    }

    fun paintBusy() {
        paintFilesBusy(feature.state.value)
    }

    fun openEditor(file: File) {
        if (!file.isFile) {
            log("not a file: ${file.name}")
            return
        }
        if (file.length() > 256_000) {
            log("file too large to edit: ${file.name}")
            return
        }
        if (RepoFiles.looksBinary(file)) {
            log("binary file: ${file.name}")
            return
        }
        saveEditor(announce = false)
        val text = file.readText()
        session.openFile = file
        savedText = text
        editing = true
        page.editor.setText(text)
        val rel = RepoFiles.rel(file, session.reposDir)
        page.filesPath.text = if (rel.isEmpty()) file.name else "~/$rel"
        page.fileList.emptyView = null
        page.filesEmpty.visibility = View.GONE
        page.fileList.visibility = View.GONE
        page.editor.visibility = View.VISIBLE
        page.filesUp.visibility = View.GONE
        page.filesOpen.visibility = View.GONE
        page.filesProjects.visibility = View.GONE
        page.filesSave.visibility = View.VISIBLE
        page.filesClose.visibility = View.VISIBLE
        syncBack()
    }

    fun closeEditor(save: Boolean) {
        if (save) saveEditor(announce = true)
        editing = false
        session.openFile = null
        savedText = ""
        page.editor.setText("")
        refreshFileList()
    }

    fun saveEditor(announce: Boolean) {
        if (!editing) return
        val file = session.openFile ?: return
        val text = page.editor.text?.toString() ?: return
        if (text == savedText) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            savedText = text
            if (announce) log("saved ~/${RepoFiles.rel(file, session.reposDir)}")
        } catch (e: Exception) {
            log("save failed: ${e.message}")
        }
    }

    fun reloadOpen(paths: List<String>) {
        val file = session.openFile ?: return
        if (!editing) return
        if (paths.none { it == file.canonicalPath }) return
        val text = file.readText()
        savedText = text
        page.editor.setText(text)
    }

    fun pickFolder() {
        val state = feature.state.value
        if (state.importBusy || state.downloadBusy) return
        launchDocumentTree()
    }

    fun startImport(uri: Uri) {
        val state = feature.state.value
        if (state.importBusy || state.downloadBusy) return
        saveEditor(announce = false)
        feature.startImport(uri)
        paintBusy()
    }

    fun showProjects() {
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            if (WorkspaceStore.downloaded().isEmpty()) {
                log("No saved projects. Clone one, or tap Open.")
            } else {
                onOpenWorkspace()
            }
            return
        }
        AlertDialog.Builder(page.root.context)
            .setTitle("Open project")
            .setItems(dirs.map { it.name }.toTypedArray()) { _, which -> openProject(dirs[which]) }
            .setNeutralButton("Edit repos") { _, _ -> onOpenWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun openProject(dir: File) {
        if (editing) closeEditor(save = true)
        WorkspaceStore.include(dir.name)
        feature.openProject(dir)
        refreshFileList()
        refreshGitIfVisible()
        log("opened ${dir.name}")
        markVibeProjectChanged()
        paintTape()
        paintProject()
        onAfterOpenProject()
    }

    private fun up() {
        if (editing) return
        feature.up()
        refreshFileList()
    }

    private fun refreshFileListFromState(filesState: FilesFeature.State) {
        val cwd = filesState.cwd
        val repos = session.reposDir
        page.filesPath.text = filesState.pathBanner ?: RepoFiles.display(cwd, repos)
        page.filesUp.isEnabled = cwd.canonicalPath != repos.canonicalPath
        val atRoot = cwd.canonicalPath == repos.canonicalPath
        val files = filesState.entries
        displayed = files
        val iconPx = (16 * density).toInt()
        page.fileList.adapter = object : ArrayAdapter<File>(page.root.context, R.layout.row_file_entry, files) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView ?: inflate.inflate(R.layout.row_file_entry, parent, false))
                    as TextView
                val file = files[position]
                row.text = file.name
                row.setCompoundDrawablesRelative(FileIcons.forFile(file, iconPx), null, null, null)
                return row
            }
        }
        page.filesEmpty.text = filesState.pathBanner ?: if (atRoot) {
            if (WorkspaceStore.downloaded().isEmpty()) {
                "Find a repo on Search, or tap Open for a folder already on this phone."
            } else {
                "No repos in ${WorkspaceStore.current().name}. Tap Projects to add downloaded repos, or find one on Search."
            }
        } else {
            "Empty folder"
        }
        page.fileList.emptyView = page.filesEmpty
        if (!editing) {
            page.editor.visibility = View.GONE
            page.fileList.visibility = View.VISIBLE
            page.filesUp.visibility = View.VISIBLE
            page.filesOpen.visibility = View.VISIBLE
            page.filesProjects.visibility = View.VISIBLE
            page.filesSave.visibility = View.GONE
            page.filesClose.visibility = View.GONE
            paintFilesBusy(filesState)
            syncBack()
        }
    }

    private fun paintFilesBusy(files: FilesFeature.State) {
        if (editing) return
        BusyUi.setEnabled(page.filesOpen, !(files.importBusy || files.downloadBusy))
    }
}
