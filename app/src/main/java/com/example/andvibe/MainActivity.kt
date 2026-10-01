package com.example.andvibe

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.andvibe.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity(), UiBridge.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private var currentProvider = Provider.OPENAI
    private var spinnerReady = false
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeEditor(save = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        store = SecretStore(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiBridge.listener = this
        onBackPressedDispatcher.addCallback(this, backCallback)
        if (store.plain && !AppState.warnedPlain) {
            AppState.warnedPlain = true
            AppState.log("API keys could not be encrypted on this device. They stay in app-private storage.")
        }
        setupConsole()
        setupFiles()
        setupVibe()
        setupNav()
        val open = AppState.openFile
        if (open != null && open.isFile) openEditor(open) else refreshFileList()
        onLog()
        onVibe()
    }

    override fun onPause() {
        saveEditor(announce = false)
        if (::store.isInitialized) saveProvider(currentProvider)
        super.onPause()
    }

    override fun onDestroy() {
        if (UiBridge.listener === this) UiBridge.listener = null
        super.onDestroy()
    }

    override fun onLog() {
        val scroll = binding.consolePage.logScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        binding.consolePage.logView.text = AppState.text()
        if (nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onFiles() {
        if (!editing) refreshFileList()
    }

    override fun onOpen(file: File) {
        openEditor(file)
        binding.bottomNav.selectedItemId = R.id.nav_files
    }

    override fun onPreview(file: File) {
        startActivity(
            Intent(this, PreviewActivity::class.java)
                .putExtra(PreviewActivity.EXTRA_PATH, file.absolutePath)
        )
    }

    override fun onVibe() {
        binding.vibePage.vibeSend.isEnabled = !AppState.vibeBusy
        if (AppState.vibeBusy) {
            binding.vibePage.vibeResult.text = "Working…"
            return
        }
        if (AppState.vibeResult.isNotEmpty()) {
            binding.vibePage.vibeResult.text = AppState.vibeResult
        }
        val paths = AppState.writtenPaths
        AppState.writtenPaths = emptyList()
        if (paths.isNotEmpty()) {
            reloadOpen(paths)
            if (!editing) refreshFileList()
        }
    }

    private fun setupConsole() {
        binding.consolePage.commandRun.setOnClickListener { submitCommand() }
        binding.consolePage.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    private fun submitCommand() {
        val line = binding.consolePage.commandInput.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        binding.consolePage.commandInput.setText("")
        AppState.log("$ $line")
        AppState.io.execute {
            try {
                Console.run(line)
            } catch (t: Throwable) {
                AppState.log("error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun setupFiles() {
        binding.filesPage.filesUp.setOnClickListener { up() }
        binding.filesPage.filesSave.setOnClickListener { saveEditor(announce = true) }
        binding.filesPage.filesClose.setOnClickListener { closeEditor(save = true) }
        binding.filesPage.fileList.setOnItemClickListener { _, _, position, _ ->
            val file = displayed.getOrNull(position) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                AppState.cwd = file
                refreshFileList()
            } else {
                openEditor(file)
            }
        }
    }

    private fun setupVibe() {
        val labels = Provider.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.row_spinner, labels)
        adapter.setDropDownViewResource(R.layout.row_spinner)
        val spinner = binding.vibePage.provider
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val next = Provider.entries[position]
                if (next == currentProvider) return
                saveProvider(currentProvider)
                currentProvider = next
                loadProvider(next)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val index = Provider.entries.indexOfFirst { it.id == store.lastProvider() }.let { if (it < 0) 0 else it }
        currentProvider = Provider.entries[index]
        spinnerReady = false
        spinner.setSelection(index)
        loadProvider(currentProvider)
        spinnerReady = true
        binding.vibePage.autoTest.isChecked = store.autoTest()
        binding.vibePage.autoTest.setOnCheckedChangeListener { _, checked -> store.setAutoTest(checked) }
        binding.vibePage.showKey.setOnCheckedChangeListener { _, checked ->
            val field = binding.vibePage.apiKey
            field.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            field.setSelection(field.text?.length ?: 0)
        }
        binding.vibePage.vibeSend.setOnClickListener { sendVibe() }
    }

    private fun setupNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val tab = when (item.itemId) {
                R.id.nav_files -> AppState.Tab.FILES
                R.id.nav_vibe -> AppState.Tab.VIBE
                else -> AppState.Tab.CONSOLE
            }
            if (tab != AppState.Tab.FILES) saveEditor(announce = false)
            applyTab(tab)
            true
        }
        applyTab(AppState.tab)
        val navId = when (AppState.tab) {
            AppState.Tab.FILES -> R.id.nav_files
            AppState.Tab.VIBE -> R.id.nav_vibe
            AppState.Tab.CONSOLE -> R.id.nav_console
        }
        if (binding.bottomNav.selectedItemId != navId) binding.bottomNav.selectedItemId = navId
    }

    private fun applyTab(tab: AppState.Tab) {
        AppState.tab = tab
        binding.consolePage.root.visibility = if (tab == AppState.Tab.CONSOLE) View.VISIBLE else View.GONE
        binding.filesPage.root.visibility = if (tab == AppState.Tab.FILES) View.VISIBLE else View.GONE
        binding.vibePage.root.visibility = if (tab == AppState.Tab.VIBE) View.VISIBLE else View.GONE
    }

    private fun refreshFileList() {
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        binding.filesPage.filesPath.text = RepoFiles.display(cwd, repos)
        binding.filesPage.filesUp.isEnabled = cwd.canonicalPath != repos.canonicalPath
        val files = cwd.listFiles()
            ?.filter { it.name != ".git" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        displayed = files
        binding.filesPage.fileList.adapter = ArrayAdapter(
            this,
            R.layout.row_file,
            files.map { if (it.isDirectory) it.name + "/" else it.name }
        )
        binding.filesPage.filesEmpty.text = if (cwd.canonicalPath == repos.canonicalPath) {
            "Clone a repo in Console:\ngit clone https://github.com/user/repo"
        } else {
            "Empty folder"
        }
        binding.filesPage.fileList.emptyView = binding.filesPage.filesEmpty
        if (!editing) {
            binding.filesPage.editor.visibility = View.GONE
            binding.filesPage.fileList.visibility = View.VISIBLE
            binding.filesPage.filesUp.visibility = View.VISIBLE
            binding.filesPage.filesSave.visibility = View.GONE
            binding.filesPage.filesClose.visibility = View.GONE
            backCallback.isEnabled = false
        }
    }

    private fun up() {
        if (editing) return
        val parent = AppState.cwd.parentFile?.canonicalFile ?: return
        val repos = AppState.reposDir.canonicalFile
        if (parent != repos && !parent.path.startsWith(repos.path + File.separator)) return
        AppState.cwd = parent
        refreshFileList()
    }

    private fun openEditor(file: File) {
        if (!file.isFile) {
            AppState.log("not a file: ${file.name}")
            return
        }
        if (file.length() > 256_000) {
            AppState.log("file too large to edit: ${file.name}")
            return
        }
        if (RepoFiles.looksBinary(file)) {
            AppState.log("binary file: ${file.name}")
            return
        }
        saveEditor(announce = false)
        val text = file.readText()
        AppState.openFile = file
        savedText = text
        editing = true
        binding.filesPage.editor.setText(text)
        val rel = RepoFiles.rel(file, AppState.reposDir)
        binding.filesPage.filesPath.text = if (rel.isEmpty()) file.name else "~/$rel"
        binding.filesPage.fileList.emptyView = null
        binding.filesPage.filesEmpty.visibility = View.GONE
        binding.filesPage.fileList.visibility = View.GONE
        binding.filesPage.editor.visibility = View.VISIBLE
        binding.filesPage.filesUp.visibility = View.GONE
        binding.filesPage.filesSave.visibility = View.VISIBLE
        binding.filesPage.filesClose.visibility = View.VISIBLE
        backCallback.isEnabled = true
    }

    private fun closeEditor(save: Boolean) {
        if (save) saveEditor(announce = true)
        editing = false
        AppState.openFile = null
        savedText = ""
        binding.filesPage.editor.setText("")
        backCallback.isEnabled = false
        refreshFileList()
    }

    private fun saveEditor(announce: Boolean) {
        if (!editing) return
        val file = AppState.openFile ?: return
        val text = binding.filesPage.editor.text?.toString() ?: return
        if (text == savedText) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            savedText = text
            if (announce) AppState.log("saved ~/${RepoFiles.rel(file, AppState.reposDir)}")
        } catch (e: Exception) {
            AppState.log("save failed: ${e.message}")
        }
    }

    private fun reloadOpen(paths: List<String>) {
        val file = AppState.openFile ?: return
        if (!editing) return
        if (paths.none { it == file.canonicalPath }) return
        val text = file.readText()
        savedText = text
        binding.filesPage.editor.setText(text)
    }

    private fun loadProvider(provider: Provider) {
        binding.vibePage.apiKey.setText(store.get(provider, "key", ""))
        binding.vibePage.model.setText(store.get(provider, "model", provider.defaultModel))
        binding.vibePage.baseUrl.setText(store.get(provider, "base", provider.defaultBase))
    }

    private fun saveProvider(provider: Provider) {
        store.save(
            provider,
            binding.vibePage.apiKey.text?.toString()?.trim().orEmpty(),
            binding.vibePage.model.text?.toString()?.trim().orEmpty(),
            binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        )
    }

    private fun sendVibe() {
        if (AppState.vibeBusy) return
        val instruction = binding.vibePage.vibePrompt.text?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) {
            binding.vibePage.vibeResult.text = "Write what you want changed."
            return
        }
        saveEditor(announce = false)
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = binding.vibePage.apiKey.text?.toString()?.trim().orEmpty()
        val model = binding.vibePage.model.text?.toString()?.trim().orEmpty()
        val base = binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        val auto = binding.vibePage.autoTest.isChecked
        val open = AppState.openFile
        val cwd = AppState.cwd
        AppState.vibeBusy = true
        AppState.vibeResult = ""
        AppState.writtenPaths = emptyList()
        UiBridge.vibeUpdate()
        AppState.io.execute {
            try {
                val root = AppState.projectRoot()
                val edit = AiClient.edit(
                    root, cwd, open, instruction, provider, key, model, base, AppState.history
                )
                val report = buildString {
                    append(edit.report)
                    if (auto && edit.written.isNotEmpty()) {
                        append("\n\n")
                        append(JsRunner.compile(root))
                        append('\n')
                        append(JsRunner.test(root))
                    }
                }
                AppState.vibeResult = report
                AppState.writtenPaths = edit.written.map { it.canonicalPath }
                AppState.log(report)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.vibeResult = msg
                AppState.writtenPaths = emptyList()
                AppState.log("vibe error: $msg")
            } finally {
                AppState.vibeBusy = false
                UiBridge.vibeUpdate()
            }
        }
    }
}
