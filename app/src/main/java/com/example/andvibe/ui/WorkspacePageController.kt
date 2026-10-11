package com.example.andvibe.ui

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.ProjectSession
import com.example.andvibe.R
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.PageWorkspaceBinding
import com.example.andvibe.databinding.RowWorkspaceBinding
import com.example.andvibe.databinding.RowWorkspaceRepoBinding
import com.example.andvibe.features.board.BoardFeature
import com.example.andvibe.features.workspace.WorkspaceFeature
import kotlinx.coroutines.launch

/**
 * Workspace overlay: open/close, repo checks/delete, list, rename/create/delete,
 * and GitHub Project link for the board.
 * Switch refusals use [WorkspaceFeature.switchBlockedBy] / [WorkspaceFeature.switchTo].
 */
class WorkspacePageController(
    private val activity: AppCompatActivity,
    private val page: PageWorkspaceBinding,
    private val feature: WorkspaceFeature,
    private val board: BoardFeature,
    private val lifecycleOwner: LifecycleOwner,
    private val session: ProjectSession,
    private val inflate: LayoutInflater,
    private val openWorkspaceBox: View,
    private val color: (Int) -> Int,
    private val selectableBackground: () -> Int,
    private val closeSettingsIfOpen: () -> Unit,
    private val syncBack: () -> Unit,
    private val onUsage: () -> Unit,
    private val onSwitched: () -> Unit,
    private val refreshFileList: () -> Unit,
    private val markVibeProjectChanged: () -> Unit,
    private val forgetWorkspaceExtras: (String) -> Unit,
    private val onBoardProjectChanged: () -> Unit,
) {
    var isOpen: Boolean = false
        private set

    fun start() {
        openWorkspaceBox.setOnClickListener { toggle() }
        page.saveWorkspace.setOnClickListener {
            saveWorkspaceName()
            onUsage()
            renderWorkspaces()
        }
        page.newWorkspace.setOnClickListener { promptNewWorkspace() }
        page.deleteWorkspace.setOnClickListener { confirmDeleteWorkspace() }
        page.boardProjectLink.setOnClickListener { linkProject() }
        page.boardProjectOpen.setOnClickListener {
            WorkspaceStore.current().project?.url?.let { openUrl(it) }
        }
        page.boardProjectUnlink.setOnClickListener {
            confirm("Unlink GitHub Project?", "Cards stay on the board. Sync links are cleared.") {
                board.unlinkProject()
                renderBoardProject()
                onBoardProjectChanged()
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feature.effectsFlow.collect { effect ->
                    when (effect) {
                        is WorkspaceFeature.Effect.Refused -> {
                            Toast.makeText(activity, effect.reason, Toast.LENGTH_SHORT).show()
                        }
                        is WorkspaceFeature.Effect.Switched -> onSwitched()
                    }
                }
            }
        }
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun open() {
        closeSettingsIfOpen()
        isOpen = true
        renderWorkspace()
        page.root.visibility = View.VISIBLE
        openWorkspaceBox.setBackgroundColor(color(R.color.line))
        syncBack()
    }

    fun close() {
        if (!isOpen) return
        saveWorkspaceName()
        isOpen = false
        page.root.visibility = View.GONE
        openWorkspaceBox.setBackgroundResource(selectableBackground())
        onUsage()
        syncBack()
    }

    fun saveWorkspaceName() {
        if (!isOpen) return
        feature.renameCurrent(page.workspaceName.text?.toString().orEmpty())
    }

    fun renderWorkspace() {
        feature.refresh()
        val ws = WorkspaceStore.current()
        page.workspaceName.setText(ws.name)
        page.deleteWorkspace.isEnabled = WorkspaceStore.workspaces().size > 1
        renderBoardProject()
        renderRepoChecks()
        renderWorkspaces()
    }

    private fun renderBoardProject() {
        val project = WorkspaceStore.current().project
        val linked = project != null
        page.boardProjectUrl.text = project?.url?.removePrefix("https://") ?: "Not linked"
        page.boardProjectUrl.setTextColor(color(if (linked) R.color.accent else R.color.muted))
        page.boardProjectLink.text = if (linked) "Change" else "Link"
        page.boardProjectOpen.isEnabled = linked
        page.boardProjectUnlink.isEnabled = linked
    }

    private fun linkProject() {
        val current = WorkspaceStore.current().project
        val input = EditText(activity).apply {
            hint = "https://github.com/users/you/projects/1"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(current?.url.orEmpty())
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Link a GitHub Project")
            .setMessage(
                "Paste an existing project's URL. Sync uses the GitHub token from Settings " +
                    "(needs the \"project\" scope). Adds a \"Card type\" field if missing."
            )
            .setView(wrap)
            .setPositiveButton("Link", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (board.linkProject(input.text?.toString().orEmpty())) {
                    dialog.dismiss()
                    renderBoardProject()
                    onBoardProjectChanged()
                    board.sync()
                } else {
                    input.error = "Use github.com/users/…/projects/N or github.com/orgs/…/projects/N"
                }
            }
        }
        dialog.show()
    }

    private fun confirm(title: String, message: String, ok: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> ok() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun renderRepoChecks() {
        val ws = WorkspaceStore.current()
        val names = WorkspaceStore.downloaded()
        page.repoEmpty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val repos = page.repoList
        repos.removeAllViews()
        for (name in names) {
            val row = RowWorkspaceRepoBinding.inflate(inflate, repos, false)
            row.repoCheck.text = name
            row.repoCheck.isChecked = name in ws.repos
            row.repoCheck.setOnCheckedChangeListener { _, checked ->
                feature.setRepoIncluded(name, checked)
                afterRepoChange()
            }
            row.repoDelete.setOnClickListener { confirmDeleteRepo(name) }
            repos.addView(row.root)
        }
    }

    fun fitAfterSwitch() {
        session.fitWorkspace(toRoot = true)
    }

    private fun canSwitchWorkspace(): Boolean {
        feature.refresh()
        val blocked = feature.state.value.switchBlockedBy ?: return true
        Toast.makeText(
            activity,
            "Wait for $blocked to finish before switching workspaces",
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }

    private fun canChangeRepos(): Boolean {
        feature.refresh()
        val blocked = feature.state.value.switchBlockedBy ?: return true
        Toast.makeText(
            activity,
            "Wait for $blocked to finish before deleting a repo",
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }

    private fun afterRepoChange() {
        if (!session.fitWorkspace()) {
            refreshFileList()
            markVibeProjectChanged()
        }
    }

    private fun renderWorkspaces() {
        val current = WorkspaceStore.current()
        val list = page.workspaceList
        list.removeAllViews()
        for (ws in WorkspaceStore.workspaces()) {
            val row = RowWorkspaceBinding.inflate(inflate, list, false)
            val active = ws.id == current.id
            row.workspaceRowName.text = ws.name
            row.workspaceRowName.setTextColor(color(if (active) R.color.accent else R.color.ink))
            row.workspaceRowMeta.text = when {
                ws.repos.isEmpty() -> "No repos"
                ws.repos.size == 1 -> "1 repo"
                else -> "${ws.repos.size} repos"
            }
            row.workspaceActive.visibility = if (active) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                if (ws.id == current.id || !canSwitchWorkspace()) return@setOnClickListener
                saveWorkspaceName()
                feature.switchTo(ws.id)
            }
            list.addView(row.root)
        }
    }

    private fun promptNewWorkspace() {
        if (!canSwitchWorkspace()) return
        saveWorkspaceName()
        val input = EditText(activity).apply {
            hint = "Name"
            setSingleLine(true)
            setText("Workspace")
        }
        AlertDialog.Builder(activity)
            .setTitle("New workspace")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                feature.create(input.text.toString())
                onSwitched()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWorkspace() {
        val ws = WorkspaceStore.current()
        if (WorkspaceStore.workspaces().size <= 1 || !canSwitchWorkspace()) return
        AlertDialog.Builder(activity)
            .setTitle(ws.name)
            .setMessage("Delete this workspace with its board, chats, saved APKs, and build history. The repos stay on the phone.")
            .setPositiveButton("Delete") { _, _ ->
                val id = ws.id
                if (WorkspaceStore.delete(id)) {
                    feature.refresh()
                    forgetWorkspaceExtras(id)
                }
                onSwitched()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteRepo(name: String) {
        if (!canChangeRepos()) return
        AlertDialog.Builder(activity)
            .setTitle(name)
            .setMessage("Delete this repo from the phone? The folder is removed for every workspace.")
            .setPositiveButton("Delete") { _, _ ->
                if (!feature.deleteRepo(name)) {
                    Toast.makeText(activity, "Could not delete $name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                afterRepoChange()
                renderRepoChecks()
                renderWorkspaces()
                onUsage()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
