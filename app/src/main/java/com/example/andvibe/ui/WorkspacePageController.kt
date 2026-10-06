package com.example.andvibe.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
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
import com.example.andvibe.features.workspace.WorkspaceFeature
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch

/**
 * Workspace overlay: open/close, repo checks, list, rename/create/delete.
 * Switch refusals use [WorkspaceFeature.switchBlockedBy] / [WorkspaceFeature.switchTo].
 */
class WorkspacePageController(
    private val activity: AppCompatActivity,
    private val page: PageWorkspaceBinding,
    private val feature: WorkspaceFeature,
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
        renderRepoChecks()
        renderWorkspaces()
    }

    fun renderRepoChecks() {
        val ws = WorkspaceStore.current()
        val names = WorkspaceStore.downloaded()
        page.repoEmpty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val repos = page.repoList
        repos.removeAllViews()
        for (name in names) {
            val box = MaterialCheckBox(activity).apply {
                text = name
                isChecked = name in ws.repos
                setOnCheckedChangeListener { _, checked ->
                    feature.setRepoIncluded(name, checked)
                    if (!session.fitWorkspace()) {
                        refreshFileList()
                        markVibeProjectChanged()
                    }
                }
            }
            repos.addView(box)
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

    private fun renderWorkspaces() {
        val current = WorkspaceStore.current()
        val list = page.workspaceList
        list.removeAllViews()
        for (ws in WorkspaceStore.workspaces()) {
            val row = inflate.inflate(R.layout.row_file, list, false) as android.widget.TextView
            row.text = ws.name
            row.setTextColor(color(if (ws.id == current.id) R.color.accent else R.color.ink))
            row.setOnClickListener {
                if (ws.id == current.id || !canSwitchWorkspace()) return@setOnClickListener
                saveWorkspaceName()
                feature.switchTo(ws.id)
            }
            list.addView(row)
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
}
