package com.example.andvibe.ui

import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
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
import com.example.andvibe.editor.CodeEditorSupport
import com.example.andvibe.editor.EditorLanguages
import com.example.andvibe.editor.EditorPrefs
import com.example.andvibe.editor.EditorSettings
import com.example.andvibe.features.files.FilesFeature
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SelectionMovement
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val editorPrefs = EditorPrefs(page.root.context)
    private var settings = editorPrefs.load()

    val isEditing: Boolean get() = editing

    /** The editor's text right now (CodeEditor's Content is a live CharSequence). */
    private val editorText: String get() = page.editor.text.toString()

    fun start() {
        startEditor()
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
        if (editorText != savedText) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text != savedText) {
            savedText = text
            replaceTextKeepingCursor(text)
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
        closeFind()
        page.editor.setText(text)
        CodeEditorSupport.apply(page.editor, page.root.context, settings, file.name)
        val rel = RepoFiles.rel(file, session.reposDir)
        page.filesPath.text = if (rel.isEmpty()) file.name else "~/$rel"
        page.fileList.emptyView = null
        page.filesEmpty.visibility = View.GONE
        page.fileList.visibility = View.GONE
        showEditorChrome(true)
        paintStatus()
        syncBack()
    }

    fun closeEditor(save: Boolean) {
        if (save) saveEditor(announce = true)
        editing = false
        session.openFile = null
        savedText = ""
        closeFind()
        page.editor.setText("")
        refreshFileList()
    }

    fun saveEditor(announce: Boolean) {
        if (!editing) return
        val file = session.openFile ?: return
        val text = editorText
        if (text == savedText) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            savedText = text
            paintStatus()
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
        replaceTextKeepingCursor(text)
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
            page.fileList.visibility = View.VISIBLE
            showEditorChrome(false)
            paintFilesBusy(filesState)
            syncBack()
        }
    }

    private fun paintFilesBusy(files: FilesFeature.State) {
        if (editing) return
        BusyUi.setEnabled(page.filesOpen, !(files.importBusy || files.downloadBusy))
    }

    // ---- Code editor (Sora) ----

    private fun startEditor() {
        val editor = page.editor
        val context = page.root.context
        CodeEditorSupport.setup(editor, context)
        CodeEditorSupport.apply(editor, context, settings, null)
        buildKeyBar()
        // Class-based subscribe: Sora's reified helpers are JVM 17 bytecode and can't inline into this JVM 11 module.
        editor.subscribeAlways(SelectionChangeEvent::class.java) { paintStatus() }
        editor.subscribeAlways(ContentChangeEvent::class.java) { paintStatus() }
        editor.subscribeAlways(PublishSearchResultEvent::class.java) { paintFindCount() }

        page.editorUndo.setOnClickListener { if (editor.canUndo()) editor.undo() }
        page.editorRedo.setOnClickListener { if (editor.canRedo()) editor.redo() }
        page.editorFind.setOnClickListener { if (page.findBar.visibility == View.VISIBLE) closeFind() else openFind() }
        page.editorGoto.setOnClickListener { goToLine() }
        startFindBar()

        editorPrefs.listen { next ->
            settings = next
            page.keyBar.visibility = if (editing && next.keyBar) View.VISIBLE else View.GONE
            CodeEditorSupport.apply(editor, context, next, session.openFile?.name)
            paintStatus()
        }
        // Grammars are ~1.5 MB of JSON: load once in the background, then upgrade the open file.
        lifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { CodeEditorSupport.init(context) }
            CodeEditorSupport.apply(editor, context, settings, session.openFile?.name)
        }
    }

    private fun showEditorChrome(show: Boolean) {
        val edit = if (show) View.VISIBLE else View.GONE
        val list = if (show) View.GONE else View.VISIBLE
        page.editorPane.visibility = edit
        page.keyBar.visibility = if (show && settings.keyBar) View.VISIBLE else View.GONE
        for (v in listOf(page.filesSave, page.filesClose, page.editorUndo, page.editorRedo, page.editorFind, page.editorGoto)) {
            v.visibility = edit
        }
        for (v in listOf(page.filesUp, page.filesOpen, page.filesProjects)) v.visibility = list
    }

    /** Swaps in [text] (e.g. after the agent wrote the file) without jumping back to the top. */
    private fun replaceTextKeepingCursor(text: String) {
        val editor = page.editor
        val line = editor.cursor.leftLine
        val column = editor.cursor.leftColumn
        editor.setText(text)
        val lines = editor.text.lineCount
        if (lines == 0) return
        val l = line.coerceIn(0, lines - 1)
        editor.setSelection(l, column.coerceIn(0, editor.text.getColumnCount(l)), false)
    }

    private fun paintStatus() {
        if (!editing) return
        val editor = page.editor
        val dirty = editorText != savedText
        val scope = session.openFile?.name?.let { EditorLanguages.scopeFor(it) }
        page.editorStatus.text = buildString {
            append("Ln ${editor.cursor.leftLine + 1}, Col ${editor.cursor.leftColumn + 1}")
            append(" · ").append(EditorLanguages.label(scope.takeIf { settings.highlight }))
            append(" · ").append(if (settings.insertSpaces) "Spaces: ${settings.tabWidth}" else "Tab: ${settings.tabWidth}")
            if (dirty) append(" · ● unsaved")
        }
    }

    /** Keys the soft keyboard buries: tab, arrows, and code punctuation. */
    private fun buildKeyBar() {
        val editor = page.editor
        val row = page.keyBarRow
        row.removeAllViews()
        fun key(label: String, desc: String, action: () -> Unit) {
            row.addView(
                TextView(page.root.context).apply {
                    text = label
                    contentDescription = desc
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 15f
                    setTextColor(page.root.context.getColor(R.color.ink))
                    minWidth = (40 * density).toInt()
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    setOnClickListener { if (editor.isEditable) action() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        key("⇥", "Tab") { editor.indentOrCommitTab() }
        key("←", "Left") { editor.moveSelection(SelectionMovement.LEFT) }
        key("→", "Right") { editor.moveSelection(SelectionMovement.RIGHT) }
        key("↑", "Up") { editor.moveSelection(SelectionMovement.UP) }
        key("↓", "Down") { editor.moveSelection(SelectionMovement.DOWN) }
        key("⇤", "Line start") { editor.moveSelection(SelectionMovement.LINE_START) }
        key("⇥|", "Line end") { editor.moveSelection(SelectionMovement.LINE_END) }
        for (symbol in KEY_SYMBOLS) key(symbol, symbol) { editor.insertText(symbol, symbol.length) }
    }

    // ---- Find / replace / go to line ----

    private fun startFindBar() {
        page.findQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runSearch()
        })
        page.findQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                findStep(forward = true)
                true
            } else {
                false
            }
        }
        for (box in listOf(page.findCase, page.findWord, page.findRegex)) {
            box.setOnCheckedChangeListener { _, _ -> runSearch() }
        }
        page.findNext.setOnClickListener { findStep(forward = true) }
        page.findPrev.setOnClickListener { findStep(forward = false) }
        page.findClose.setOnClickListener { closeFind() }
        page.findReplaceToggle.setOnClickListener {
            page.replaceRow.visibility = if (page.replaceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        page.replaceOne.setOnClickListener {
            val searcher = page.editor.searcher
            if (!searcher.hasQuery()) return@setOnClickListener
            if (!searcher.isMatchedPositionSelected) searcher.gotoNext()
            if (searcher.isMatchedPositionSelected) searcher.replaceCurrentMatch(page.replaceText.text.toString())
        }
        page.replaceAll.setOnClickListener {
            val searcher = page.editor.searcher
            if (searcher.hasQuery()) searcher.replaceAll(page.replaceText.text.toString())
        }
    }

    private fun openFind() {
        if (!editing) return
        page.findBar.visibility = View.VISIBLE
        val selected = page.editor.cursor.let { c ->
            if (c.isSelected && c.leftLine == c.rightLine) {
                page.editor.text.subSequence(c.left, c.right).toString()
            } else {
                null
            }
        }
        if (!selected.isNullOrEmpty()) page.findQuery.setText(selected)
        page.findQuery.requestFocus()
        page.findQuery.selectAll()
        runSearch()
    }

    private fun closeFind() {
        page.findBar.visibility = View.GONE
        page.replaceRow.visibility = View.GONE
        runCatching { page.editor.searcher.stopSearch() }
        page.editor.invalidate()
    }

    private fun runSearch() {
        val query = page.findQuery.text?.toString().orEmpty()
        val searcher = page.editor.searcher
        if (query.isEmpty() || page.findBar.visibility != View.VISIBLE) {
            runCatching { searcher.stopSearch() }
            page.findCount.text = ""
            return
        }
        val type = when {
            page.findRegex.isChecked -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            page.findWord.isChecked -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        try {
            searcher.search(query, EditorSearcher.SearchOptions(type, !page.findCase.isChecked))
        } catch (e: Exception) {
            // Bad regex while typing.
            runCatching { searcher.stopSearch() }
            page.findCount.text = "bad regex"
        }
    }

    private fun findStep(forward: Boolean) {
        val searcher = page.editor.searcher
        if (!searcher.hasQuery()) return
        if (forward) searcher.gotoNext() else searcher.gotoPrevious()
        paintFindCount()
    }

    private fun paintFindCount() {
        val searcher = page.editor.searcher
        if (!searcher.hasQuery()) {
            page.findCount.text = ""
            return
        }
        val total = searcher.matchedPositionCount
        val index = searcher.currentMatchedPositionIndex
        page.findCount.text = when {
            total == 0 -> "0"
            index >= 0 -> "${index + 1}/$total"
            else -> "$total"
        }
    }

    private fun goToLine() {
        if (!editing) return
        val editor = page.editor
        val lines = editor.text.lineCount
        val input = EditText(page.root.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1–$lines"
            setText((editor.cursor.leftLine + 1).toString())
            selectAll()
        }
        val pad = (20 * density).toInt()
        val wrap = LinearLayout(page.root.context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(page.root.context)
            .setTitle("Go to line")
            .setView(wrap)
            .setPositiveButton("Go") { _, _ ->
                val line = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                editor.setSelection((line - 1).coerceIn(0, lines - 1), 0, true)
                editor.requestFocus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        val KEY_SYMBOLS = listOf(
            "{", "}", "(", ")", "[", "]", ";", ":", ",", ".", "=", "\"", "'", "<", ">",
            "/", "\\", "|", "&", "!", "?", "+", "-", "*", "%", "_", "$", "#", "@", "`", "~",
        )
    }
}
