package com.example.andvibe.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.FileIcons
import com.example.andvibe.ProjectSession
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.databinding.PageGitBinding
import com.example.andvibe.databinding.RowGitCommitBinding
import com.example.andvibe.databinding.RowGitFileBinding
import com.example.andvibe.databinding.RowGitSectionBinding
import com.example.andvibe.features.git.GitFeature
import com.example.andvibe.tasks.AppDispatchers
import com.google.android.material.tabs.TabLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Git tab chrome: status lists, menus, branches, and releases.
 */
class GitPageController(
    private val activity: AppCompatActivity,
    private val page: PageGitBinding,
    private val feature: GitFeature,
    private val session: ProjectSession,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val providerCreds: (Provider) -> GitFeature.Creds,
    private val saveProvider: () -> Unit,
    private val currentProvider: () -> Provider,
    private val saveEditor: () -> Unit,
    private val openFilesTab: () -> Unit,
    private val refreshFileList: () -> Unit,
    private val openEditor: (File) -> Unit,
    private val log: (String) -> Unit,
    private val syncBack: () -> Unit,
    private val paintBusy: () -> Unit,
) {
    private var bindingDraft = false
    private var scrollY = 0
    private val collapsed = mutableSetOf<String>()

    fun start() {
        if (page.gitMessage.text.isNullOrEmpty()) {
            bindingDraft = true
            page.gitMessage.setText(feature.commitDraft())
            bindingDraft = false
        }
        page.gitMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (bindingDraft) return
                feature.saveCommitDraft(s?.toString().orEmpty())
            }
        })
        page.gitRefresh.setOnClickListener { feature.refreshWithDetailClear() }
        page.gitPull.setOnClickListener { feature.pull() }
        page.gitPush.setOnClickListener { feature.push() }
        page.gitBranch.setOnClickListener { showBranches() }
        page.gitMore.setOnClickListener { showGitMenu(it) }
        page.gitCommit.setOnClickListener {
            val message = page.gitMessage.text?.toString()?.trim().orEmpty()
            if (message.isEmpty()) {
                page.gitSummary.text = "Write a commit message."
            } else {
                feature.commit(message)
            }
        }
        page.gitSuggest.setOnClickListener {
            val snap = feature.state.value.snapshot
            if (snap == null || !snap.isRepo || snap.changes.isEmpty()) {
                page.gitSummary.text = "Nothing to describe."
                return@setOnClickListener
            }
            saveProvider()
            feature.suggestMessage(providerCreds(currentProvider()))
        }
        page.gitDetailClose.setOnClickListener { feature.closeDetail() }
        page.gitTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showGitTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) showGitTab(1)
            }
        })
        page.createRelease.setOnClickListener { createRelease() }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { feature.state.collect { render(it) } }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            is GitFeature.Effect.SetCommitDraft -> {
                                bindingDraft = true
                                page.gitMessage.setText(effect.text)
                                bindingDraft = false
                            }
                        }
                    }
                }
            }
        }
    }

    fun onTabVisible() {
        feature.syncBusy()
        if (feature.state.value.detail == null) feature.refresh()
    }

    fun onExternalUpdate() {
        feature.syncBusy()
    }

    fun hasDetail(): Boolean = feature.state.value.detail != null

    fun closeDetail() {
        feature.closeDetail()
    }

    private fun showGitTab(index: Int) {
        val releases = index == 1
        page.gitStatus.visibility = if (releases) View.GONE else View.VISIBLE
        page.gitReleases.visibility = if (releases) View.VISIBLE else View.GONE
        if (releases) loadReleases()
    }

    private fun loadReleases() {
        scope.launch(dispatchers.repo) {
            val tags = runCatching { GitOps.tags(session.cwd, session.reposDir) }.getOrDefault(emptyList())
            activity.runOnUiThread { renderReleases(tags) }
        }
    }

    private fun renderReleases(tags: List<GitOps.TagLine>) {
        val list = page.releaseList
        list.removeAllViews()
        page.releaseEmpty.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
        for (tag in tags) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(TextView(activity).apply {
                text = tag.name
                setTextColor(activity.getColor(R.color.accent))
                textSize = 15f
            })
            card.addView(TextView(activity).apply {
                text = tag.whenText
                setTextColor(activity.getColor(R.color.muted))
                textSize = 12f
            })
            if (tag.subject.isNotBlank() && tag.subject != tag.name) {
                card.addView(TextView(activity).apply {
                    text = tag.subject
                    setTextColor(activity.getColor(R.color.ink))
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = dp(8)
            list.addView(card, params)
        }
    }

    private fun createRelease() {
        if (feature.state.value.busy) return
        val name = page.releaseName.text?.toString()?.trim().orEmpty()
        val notes = page.releaseNotes.text?.toString()?.trim().orEmpty()
        feature.createRelease(name, notes) { text, tags ->
            renderReleases(tags)
            if (text.startsWith("tagged ")) {
                page.releaseName.setText("")
                page.releaseNotes.setText("")
            }
            onExternalUpdate()
        }
        onExternalUpdate()
    }

    private fun render(state: GitFeature.State) {
        val snap = state.snapshot
        val showDetail = state.detail != null
        page.gitBranch.text = when {
            snap == null || snap.branch.isBlank() -> "No repository"
            else -> snap.branch
        }
        page.gitSync.text = when {
            snap?.isRepo != true -> ""
            !snap.upstream -> "no upstream"
            else -> "↓${snap.behind} ↑${snap.ahead}"
        }
        page.gitSummary.text = when {
            state.busy -> "Working…"
            snap == null -> "Open a project, then refresh."
            !snap.isRepo -> snap.summary
            else -> snap.remote.ifBlank { "No remote" }
        }
        val staged = snap?.changes?.count { it.staged } ?: 0
        page.gitMessage.hint = if (snap?.isRepo == true && snap.branch.isNotBlank()) {
            "Message (commit on ${snap.branch})"
        } else {
            "Message"
        }
        page.gitCommit.text = if (staged > 0) "Commit $staged" else "Commit"
        if (state.clearCommitMessage) {
            bindingDraft = true
            page.gitMessage.setText("")
            bindingDraft = false
            feature.saveCommitDraft("")
            feature.ackClearCommitMessage()
        }
        page.gitDetail.text = state.detail.orEmpty()
        page.gitDetailScroll.visibility = if (showDetail) View.VISIBLE else View.GONE
        page.gitDetailClose.visibility = if (showDetail) View.VISIBLE else View.GONE
        page.gitComposer.visibility = if (showDetail) View.GONE else View.VISIBLE
        val showLists = !showDetail && snap?.isRepo == true
        page.gitScroll.visibility = if (showLists) View.VISIBLE else View.GONE
        page.gitEmpty.text = when {
            snap == null -> "Open a project from Files."
            !snap.isRepo -> "${snap.summary}\n\nUse ⋯ › Initialize repository to start tracking."
            else -> "No changes"
        }
        page.gitEmpty.visibility = if (!showDetail && !showLists) View.VISIBLE else View.GONE
        if (showLists) {
            scrollY = page.gitScroll.scrollY
            fillLists(page.gitLists, snap!!)
            page.gitScroll.post { page.gitScroll.scrollTo(0, scrollY) }
        }
        val enabled = !state.busy
        for (button in listOf(page.gitRefresh, page.gitPull, page.gitPush, page.gitMore)) {
            BusyUi.setEnabled(button, enabled)
        }
        BusyUi.setEnabled(page.gitBranch, enabled)
        BusyUi.setEnabled(page.gitCommit, enabled)
        BusyUi.setEnabled(page.gitSuggest, enabled && snap?.isRepo == true && snap.changes.isNotEmpty())
        BusyUi.setEnabled(page.createRelease, enabled)
        BusyUi.setEnabled(page.releaseName, enabled)
        BusyUi.setEnabled(page.releaseNotes, enabled)
        paintBusy()
        syncBack()
    }

    private fun fillLists(parent: LinearLayout, snap: GitOps.Snapshot) {
        parent.removeAllViews()
        val staged = snap.changes.filter { it.staged }
        val unstaged = snap.changes.filter { it.unstaged }
        if (staged.isNotEmpty()) {
            val open = gitSection(parent, "Staged Changes", staged.size, R.drawable.ic_git_minus, "Unstage all") {
                feature.unstageAll()
            }
            if (open) staged.forEach { gitFileRow(parent, it, stage = false) }
        }
        if (unstaged.isNotEmpty()) {
            val open = gitSection(parent, "Changes", unstaged.size, R.drawable.ic_git_plus, "Stage all") {
                feature.stageAll()
            }
            if (open) unstaged.forEach { gitFileRow(parent, it, stage = true) }
        }
        if (snap.changes.isEmpty()) gitNote(parent, "No changes")
        if (gitSection(parent, "Commits", snap.commits.size, 0, "", null)) {
            if (snap.commits.isEmpty()) gitNote(parent, "No commits yet")
            else snap.commits.forEach { gitCommitRow(parent, it) }
        }
    }

    private fun gitSection(
        parent: LinearLayout,
        title: String,
        count: Int,
        actionIcon: Int,
        actionLabel: String,
        action: (() -> Unit)?,
    ): Boolean {
        val row = RowGitSectionBinding.inflate(activity.layoutInflater, parent, false)
        val open = title !in collapsed
        row.sectionTitle.text = title.uppercase()
        row.sectionChevron.rotation = if (open) 90f else 0f
        row.sectionCount.text = count.toString()
        row.root.setOnClickListener {
            if (!collapsed.remove(title)) collapsed.add(title)
            val snap = feature.state.value.snapshot ?: return@setOnClickListener
            if (feature.state.value.detail != null || !snap.isRepo) return@setOnClickListener
            scrollY = page.gitScroll.scrollY
            fillLists(page.gitLists, snap)
            page.gitScroll.post { page.gitScroll.scrollTo(0, scrollY) }
        }
        if (action != null) {
            row.sectionAction.visibility = View.VISIBLE
            row.sectionAction.setImageResource(actionIcon)
            row.sectionAction.contentDescription = actionLabel
            val busy = feature.state.value.busy
            BusyUi.setEnabled(row.sectionAction, !busy)
            row.sectionAction.setOnClickListener {
                if (feature.state.value.busy) return@setOnClickListener
                saveEditor()
                action()
            }
        }
        parent.addView(row.root)
        return open
    }

    private fun gitNote(parent: LinearLayout, text: String) {
        val view = TextView(activity)
        view.text = text
        view.setTextColor(ContextCompat.getColor(activity, R.color.muted))
        view.textSize = 12f
        view.setPadding(dp(22), dp(6), 0, dp(6))
        parent.addView(view)
    }

    private fun gitFileRow(parent: LinearLayout, change: GitOps.Change, stage: Boolean) {
        val row = RowGitFileBinding.inflate(activity.layoutInflater, parent, false)
        val code = if (stage) change.code.getOrElse(1) { ' ' } else change.code.getOrElse(0) { ' ' }
        val (letter, color) = when (code) {
            'M' -> "M" to R.color.git_modified
            'A' -> "A" to R.color.git_added
            '?' -> "U" to R.color.git_added
            'D' -> "D" to R.color.git_deleted
            'U' -> "!" to R.color.git_conflict
            else -> code.toString().trim() to R.color.muted
        }
        val tint = activity.getColor(color)
        val name = change.path.substringAfterLast('/')
        val dir = change.path.substringBeforeLast('/', "")
        val label = android.text.SpannableStringBuilder(name)
        if (code == 'D') {
            label.setSpan(android.text.style.StrikethroughSpan(), 0, name.length, 0)
        }
        if (dir.isNotEmpty()) {
            val start = label.length
            label.append("  ").append(dir)
            label.setSpan(
                android.text.style.ForegroundColorSpan(activity.getColor(R.color.muted)),
                start,
                label.length,
                0,
            )
            label.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, label.length, 0)
        }
        row.gitPath.text = label
        row.gitPath.setTextColor(tint)
        row.gitStatus.text = letter
        row.gitStatus.setTextColor(tint)
        row.gitIcon.setImageDrawable(FileIcons.forFile(File(change.path), dp(16)))
        row.gitMark.setImageResource(if (stage) R.drawable.ic_git_plus else R.drawable.ic_git_minus)
        row.gitMark.contentDescription = if (stage) "Stage" else "Unstage"
        row.gitDiscard.visibility = if (stage) View.VISIBLE else View.GONE
        val enabled = !feature.state.value.busy
        BusyUi.setEnabled(row.root, enabled)
        BusyUi.setEnabled(row.gitMark, enabled)
        BusyUi.setEnabled(row.gitDiscard, enabled)
        row.gitDiscard.setOnClickListener { if (enabled) confirmDiscard(change) }
        row.root.setOnClickListener { if (enabled) showChangeMenu(change) }
        row.gitMark.setOnClickListener {
            if (!enabled) return@setOnClickListener
            saveEditor()
            if (stage) feature.stage(change.path) else feature.unstage(change.path)
        }
        parent.addView(row.root)
    }

    private fun gitCommitRow(parent: LinearLayout, commit: GitOps.CommitLine) {
        val row = RowGitCommitBinding.inflate(activity.layoutInflater, parent, false)
        row.commitSubject.text = commit.subject
        row.commitMeta.text = "${commit.id} · ${commit.whenText}"
        row.root.setOnClickListener {
            feature.showDetail("${commit.id} ${commit.whenText}\n${commit.subject}")
        }
        parent.addView(row.root)
    }

    private fun showGitMenu(anchor: View) {
        val menu = PopupMenu(activity, anchor)
        val actions = listOf(
            "Stage all" to { saveEditor(); feature.stageAll() },
            "Unstage all" to { saveEditor(); feature.unstageAll() },
            "Fetch" to { saveEditor(); feature.fetch() },
            "Checkout branch…" to { showBranches() },
            "Full log" to { saveEditor(); feature.showHistory() },
            "Initialize repository" to { saveEditor(); feature.initRepo() },
        )
        actions.forEachIndexed { i, (label, _) -> menu.menu.add(0, i, i, label) }
        menu.setOnMenuItemClickListener { item ->
            actions.getOrNull(item.itemId)?.second?.invoke()
            true
        }
        menu.show()
    }

    private fun showBranches() {
        if (feature.state.value.busy) return
        scope.launch(dispatchers.repo) {
            val names = try {
                GitOps.branches(session.cwd, session.reposDir)
            } catch (t: Throwable) {
                log(t.message ?: "git failed")
                null
            } ?: return@launch
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val items = (names + "New branch").toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Branch")
                    .setItems(items) { _, which ->
                        if (which == names.size) promptNewBranch()
                        else {
                            saveEditor()
                            feature.checkout(names[which])
                        }
                    }
                    .show()
            }
        }
    }

    private fun promptNewBranch() {
        val input = EditText(activity).apply {
            hint = "branch name"
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setTitle("New branch")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                saveEditor()
                feature.createBranch(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangeMenu(change: GitOps.Change) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        if (change.unstaged) add("Diff") { showGitDiff(change, staged = false) }
        if (change.staged) add("Diff staged") { showGitDiff(change, staged = true) }
        if (change.unstaged) add("Stage") {
            saveEditor()
            feature.stage(change.path)
        }
        if (change.staged) add("Unstage") {
            saveEditor()
            feature.unstage(change.path)
        }
        add("Discard") { confirmDiscard(change) }
        add("Open") { openGitPath(change) }
        AlertDialog.Builder(activity)
            .setTitle(change.path)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showGitDiff(change: GitOps.Change, staged: Boolean) {
        saveEditor()
        feature.showDiff(change.path, staged)
    }

    private fun confirmDiscard(change: GitOps.Change) {
        AlertDialog.Builder(activity)
            .setTitle("Discard changes")
            .setMessage(change.path)
            .setPositiveButton("Discard") { _, _ ->
                saveEditor()
                feature.discard(change.path)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openGitPath(change: GitOps.Change) {
        val root = RepoFiles.gitRoot(session.cwd, session.reposDir) ?: return
        val file = File(root, change.path)
        if (file.isDirectory) {
            session.cwd = file
            openFilesTab()
            refreshFileList()
            return
        }
        if (!file.isFile) {
            log("missing ${change.path}")
            return
        }
        openFilesTab()
        openEditor(file)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
