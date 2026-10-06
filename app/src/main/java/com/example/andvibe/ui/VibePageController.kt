package com.example.andvibe.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.ChatStore
import com.example.andvibe.ProjectMentions
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.SecretStore
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.PageVibeBinding
import com.example.andvibe.features.vibe.VibeFeature
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Vibe tab: chat, history, provider chrome, @-mentions, and repo picker.
 */
class VibePageController(
    private val activity: AppCompatActivity,
    private val page: PageVibeBinding,
    private val feature: VibeFeature,
    private val store: SecretStore,
    private val inflate: android.view.LayoutInflater,
    private val color: (Int) -> Int,
    private val dp: (Int) -> Int,
    private val screenWidth: () -> Int,
    private val vibeRoot: () -> File?,
    private val agentStopping: () -> Boolean,
    private val beforeSend: () -> Unit,
    private val ensureProject: (File) -> Unit,
    private val openWorkspace: () -> Unit,
    private val openProject: (File) -> Unit,
    private val sessionCwd: () -> File,
    private val sessionOpenFile: () -> File?,
    private val reloadOpenFiles: (List<String>) -> Unit,
    private val refreshFileListIfNeeded: () -> Unit,
    private val paintBusy: () -> Unit,
    private val log: (String) -> Unit,
    private val onProviderChanged: () -> Unit,
) {
    private var draftRestored = false
    private var mentionPopup: ListPopupWindow? = null
    private var mentionEditing = false
    private val mentionNames = mutableListOf<String>()
    private var provider = Provider.OPENAI
    private var spinnerReady = false

    private val mentionWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (mentionEditing || s == null) return
            refreshMentionSpans(s)
            updateMentionPopup()
            feature.setDraft(s.toString())
        }
    }

    fun start() {
        setupProvider()
        page.autoTest.isChecked = store.autoTest()
        page.autoTest.setOnCheckedChangeListener { _, checked -> store.setAutoTest(checked) }
        page.vibeMention.setOnClickListener { openMentionPicker(force = true) }
        page.vibePrompt.addTextChangedListener(mentionWatcher)
        page.vibePrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInstruction(page.vibePrompt.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        page.vibeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showVibeTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        page.vibeSend.setOnClickListener { onSendClick() }
        page.vibeRepoPick.setOnClickListener { pickRepoClick() }
        page.vibeRepoBar.setOnClickListener { pickRepoClick() }
        page.newChat.setOnClickListener {
            if (feature.state.value.busy) return@setOnClickListener
            if (feature.startNewChat()) {
                page.vibeTabs.getTabAt(0)?.select()
            }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { feature.state.collect { render(it) } }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            is VibeFeature.Effect.ReloadOpenFiles -> reloadOpenFiles(effect.paths)
                            VibeFeature.Effect.RefreshFileList -> refreshFileListIfNeeded()
                        }
                    }
                }
            }
        }
    }

    fun currentProvider(): Provider = provider

    fun saveCurrentProvider() {
        saveProvider(provider)
    }

    fun loadCurrentProvider() {
        loadProvider(provider)
    }

    fun selectChatTab() {
        page.vibeTabs.getTabAt(0)?.select()
    }

    fun onTabVisible() {
        feature.syncBusy()
    }

    fun onUsageSync() {
        if (feature.syncWorkspace()) {
            selectChatTab()
        }
    }

    fun isBusy(): Boolean = feature.state.value.busy

    fun sendInstruction(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) return
        page.vibePrompt.setText("")
        feature.setDraft("")
        beforeSend()
        val root = vibeRoot()
        if (root != null) ensureProject(root)
        saveProvider(provider)
        feature.send(trimmed, vibeCreds(), sendContext())
    }

    fun renderHistory() {
        val list = page.historyList
        list.removeAllViews()
        val chats = ChatStore.chats()
        page.historyEmpty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
        val busy = feature.state.value.busy
        BusyUi.setEnabled(page.newChat, !busy)
        val active = ChatStore.activeId()
        val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        for (chat in chats) {
            val row = inflate.inflate(R.layout.row_card, list, false)
            val title = row.findViewById<TextView>(R.id.cardTitle)
            title.text = chat.title
            title.maxLines = 2
            if (chat.id == active) title.setTextColor(color(R.color.accent))
            val messages = chat.turns.count { it.first != "steps" }
            row.findViewById<TextView>(R.id.cardBody).text =
                "${stamp.format(Date(chat.updated))} · $messages messages"
            row.setOnClickListener {
                if (feature.state.value.busy) {
                    Toast.makeText(activity, "Wait for the current reply to finish", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (feature.openChat(chat.id)) {
                    selectChatTab()
                }
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(activity)
                    .setTitle(chat.title)
                    .setMessage("Delete this chat.")
                    .setPositiveButton("Delete") { _, _ ->
                        feature.deleteChat(chat.id)
                        renderHistory()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            list.addView(row)
        }
    }

    private fun setupProvider() {
        val labels = Provider.entries.map { it.label }
        val adapter = ArrayAdapter(activity, R.layout.row_spinner, labels)
        adapter.setDropDownViewResource(R.layout.row_spinner)
        page.provider.adapter = adapter
        page.provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val next = Provider.entries[position]
                if (next == provider) return
                saveProvider(provider)
                provider = next
                loadProvider(next)
                onProviderChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val index = Provider.entries.indexOfFirst { it.id == store.lastProvider() }.let { if (it < 0) 0 else it }
        provider = Provider.entries[index]
        spinnerReady = false
        page.provider.setSelection(index)
        loadProvider(provider)
        spinnerReady = true
    }

    private fun vibeCreds(): VibeFeature.Creds {
        val p = provider
        return VibeFeature.Creds(
            p,
            store.get(p, "key", ""),
            page.model.text?.toString()?.trim().orEmpty().ifBlank { p.defaultModel },
            page.baseUrl.text?.toString()?.trim().orEmpty().ifBlank { p.defaultBase },
        )
    }

    private fun sendContext(): VibeFeature.SendContext {
        return VibeFeature.SendContext(
            startRoot = vibeRoot(),
            cwd = sessionCwd(),
            open = sessionOpenFile(),
            workspaceRepos = WorkspaceStore.activeRepos(),
        )
    }

    private fun loadProvider(provider: Provider) {
        page.model.setText(store.get(provider, "model", provider.defaultModel))
        page.baseUrl.setText(store.get(provider, "base", provider.defaultBase))
    }

    private fun saveProvider(provider: Provider) {
        store.saveChoice(
            provider,
            page.model.text?.toString()?.trim().orEmpty(),
            page.baseUrl.text?.toString()?.trim().orEmpty(),
        )
    }

    private fun showVibeTab(index: Int) {
        val model = index == 2
        if (!model) saveProvider(provider)
        page.chatPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        page.historyPane.visibility = if (index == 1) View.VISIBLE else View.GONE
        page.modelPane.visibility = if (model) View.VISIBLE else View.GONE
        if (index == 1) renderHistory()
    }

    private fun onSendClick() {
        if (feature.state.value.busy) {
            feature.send("", vibeCreds(), sendContext())
            return
        }
        sendInstruction(page.vibePrompt.text?.toString()?.trim().orEmpty())
    }

    private fun pickRepoClick() {
        if (feature.state.value.busy) return
        pickVibeRepo()
    }

    private fun pickVibeRepo() {
        if (feature.state.value.busy) return
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            openWorkspace()
            return
        }
        val current = vibeRoot()?.name
        AlertDialog.Builder(activity)
            .setTitle("Repo for Vibe")
            .setSingleChoiceItems(
                dirs.map { it.name }.toTypedArray(),
                dirs.indexOfFirst { it.name == current },
            ) { dialog, which ->
                dialog.dismiss()
                if (dirs[which].name != current) openProject(dirs[which])
            }
            .setNeutralButton("Edit repos") { _, _ -> openWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mentionColors(): Triple<Int, Int, Int> {
        val accent = color(R.color.accent)
        val fill = ColorUtils.setAlphaComponent(accent, 0x2A)
        return Triple(fill, accent, accent)
    }

    private fun refreshMentionSpans(text: Editable) {
        val known = WorkspaceStore.activeRepos().map { it.name }
        val (fill, stroke, tint) = mentionColors()
        mentionEditing = true
        try {
            ProjectMentions.applySpans(text, known, fill, stroke, tint)
        } finally {
            mentionEditing = false
        }
    }

    private fun updateMentionPopup() {
        val prompt = page.vibePrompt
        val query = ProjectMentions.atQuery(prompt.text ?: "", prompt.selectionStart)
        if (query == null) {
            mentionPopup?.dismiss()
            return
        }
        showMentionChoices(query.query, query.start, query.start + 1 + query.query.length)
    }

    private fun openMentionPicker(force: Boolean) {
        if (feature.state.value.busy) return
        val others = ProjectMentions.filterRepos(
            WorkspaceStore.activeRepos(),
            "",
            exclude = vibeRoot()?.name,
        )
        if (others.isEmpty()) {
            if (WorkspaceStore.activeRepos().isEmpty()) openWorkspace()
            else log("No other projects in this workspace to reference")
            return
        }
        if (!force) {
            updateMentionPopup()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Reference project")
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val prompt = page.vibePrompt
                val cursor = prompt.selectionStart.coerceAtLeast(0)
                val q = ProjectMentions.atQuery(prompt.text ?: "", cursor)
                if (q != null) {
                    insertMention(others[which].name, q.start, cursor)
                } else {
                    insertMention(others[which].name, cursor, cursor)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMentionChoices(filter: String, replaceStart: Int, replaceEnd: Int) {
        val dirs = ProjectMentions.filterRepos(
            WorkspaceStore.activeRepos(),
            filter,
            exclude = vibeRoot()?.name,
        )
        if (dirs.isEmpty()) {
            mentionPopup?.dismiss()
            return
        }
        mentionNames.clear()
        mentionNames.addAll(dirs.map { it.name })
        val prompt = page.vibePrompt
        val popup = mentionPopup ?: ListPopupWindow(activity).also {
            it.anchorView = prompt
            it.isModal = false
            mentionPopup = it
        }
        popup.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, mentionNames))
        popup.setOnItemClickListener { _, _, position, _ ->
            val name = mentionNames.getOrNull(position) ?: return@setOnItemClickListener
            val q = ProjectMentions.atQuery(prompt.text ?: "", prompt.selectionStart)
            val start = q?.start ?: replaceStart
            val end = prompt.selectionStart.coerceAtLeast(start)
            insertMention(name, start, end)
        }
        popup.width = prompt.width.coerceAtLeast(280)
        popup.height = ListPopupWindow.WRAP_CONTENT
        if (!popup.isShowing) popup.show()
    }

    private fun insertMention(name: String, replaceStart: Int, replaceEnd: Int) {
        mentionPopup?.dismiss()
        val prompt = page.vibePrompt
        val known = WorkspaceStore.activeRepos().map { it.name }
        val (fill, stroke, tint) = mentionColors()
        mentionEditing = true
        try {
            val (spanned, cursor) = ProjectMentions.insert(
                prompt.text ?: "",
                replaceStart,
                replaceEnd,
                name,
                known,
                fill,
                stroke,
                tint,
            )
            prompt.removeTextChangedListener(mentionWatcher)
            prompt.setText(spanned)
            prompt.setSelection(cursor.coerceIn(0, spanned.length))
            prompt.addTextChangedListener(mentionWatcher)
        } finally {
            mentionEditing = false
        }
        prompt.requestFocus()
    }

    private fun render(state: VibeFeature.State) {
        if (!draftRestored && !mentionEditing) {
            draftRestored = true
            val draft = state.draftPrompt
            if (draft.isNotEmpty()) {
                page.vibePrompt.setText(draft)
                page.vibePrompt.setSelection(draft.length)
            }
        }
        renderRepo(state)
        renderChat(state)
        renderSend(state)
        paintBusy()
        val paths = feature.consumeWrittenPaths()
        if (paths.isNotEmpty()) {
            reloadOpenFiles(paths)
            refreshFileListIfNeeded()
        }
    }

    private fun renderRepo(state: VibeFeature.State) {
        state.projectEpoch
        val repo = if (state.busy) state.runRepo else vibeRoot()
        page.vibeRepo.text = repo?.name ?: "No repo selected"
        page.vibeRepo.setTextColor(color(if (repo == null) R.color.muted else R.color.accent))
        val busy = state.busy
        BusyUi.setEnabled(page.vibeRepoPick, !busy)
        BusyUi.setEnabled(page.vibeRepoBar, !busy)
        BusyUi.setEnabled(page.vibeMention, !busy)
        BusyUi.setEnabled(page.provider, !busy)
        BusyUi.setEnabled(page.vibePrompt, !busy)
        BusyUi.setEnabled(page.autoTest, !busy)
        page.vibePrompt.hint = if (repo == null) {
            "Pick a project, or ask for a new one · @ to reference"
        } else {
            "Message ${repo.name} · @ to reference"
        }
    }

    private fun renderChat(state: VibeFeature.State) {
        val list = page.chatList
        list.removeAllViews()
        val turns = state.chat
        page.chatEmpty.visibility = if (turns.isEmpty() && !state.busy) View.VISIBLE else View.GONE
        for ((role, text) in turns) list.addView(chatBubble(role, text))
        if (state.busy) {
            val steps = state.agentSteps.takeLast(12)
            if (steps.isNotEmpty()) list.addView(chatBubble("steps", steps.joinToString("\n")))
            list.addView(chatBubble("assistant", "Working…"))
        }
        page.chatScroll.post { page.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderSend(state: VibeFeature.State) {
        val send = page.vibeSend
        when {
            !state.busy -> {
                BusyUi.setEnabled(send, true)
                send.text = "Send"
            }
            agentStopping() -> {
                BusyUi.setEnabled(send, false)
                send.text = "Stop"
            }
            else -> {
                BusyUi.setEnabled(send, true)
                send.text = "Stop"
            }
        }
    }

    private fun chatBubble(role: String, text: String): View {
        val user = role == "user"
        val steps = role == "steps"
        val bubble = TextView(activity).apply {
            this.text = text
            if (steps) {
                setTextColor(color(R.color.muted))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(dp(4), dp(4), dp(4), dp(4))
            } else {
                setTextColor(color(R.color.ink))
                textSize = 15f
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(8)
        params.gravity = if (user) Gravity.END else Gravity.START
        bubble.maxWidth = (screenWidth() * 0.82f).toInt()
        bubble.layoutParams = params
        return bubble
    }
}
