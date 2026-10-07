package com.example.andvibe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.R
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.PageBoardBinding
import com.example.andvibe.databinding.RowCardBinding
import com.example.andvibe.features.board.BoardFeature
import com.example.andvibe.features.board.BoardRecorder
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Renders the Board tab columns, voice capture, and card dialogs.
 * Build→Vibe goes through [startVibeFromBoard].
 */
class BoardPageController(
    private val activity: AppCompatActivity,
    private val page: PageBoardBinding,
    private val feature: BoardFeature,
    private val inflate: LayoutInflater,
    private val startVibeFromBoard: (String) -> Unit,
    private val providerCreds: () -> BoardFeature.Creds,
    private val saveProvider: () -> Unit,
    private val requestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
) {
    private var recorder: BoardRecorder? = null
    private var lastRequirements = ""

    fun start() {
        page.addIdea.setOnClickListener { editCard(null, WorkspaceStore.Column.IDEA.id) }
        page.addBug.setOnClickListener { editCard(null, WorkspaceStore.Column.BUG.id) }
        page.addSolution.setOnClickListener { editCard(null, WorkspaceStore.Column.SOLUTION.id) }
        page.addCompleted.setOnClickListener { editCard(null, WorkspaceStore.Column.COMPLETED.id) }
        page.boardRecord.setOnClickListener { toggleRecord() }
        recorder = BoardRecorder(activity)

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    feature.state.collect { state ->
                        renderBoard(state)
                    }
                }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            is BoardFeature.Effect.SendToVibe -> startVibeFromBoard(effect.instruction)
                            is BoardFeature.Effect.EditCard -> editCard(effect.card, effect.card.column)
                            is BoardFeature.Effect.Toast ->
                                Toast.makeText(activity, effect.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        feature.refresh()
    }

    fun stopSpeech() {
        val rec = recorder ?: return
        if (!rec.isRecording()) return
        rec.cancel()
        feature.setListening(false)
    }

    fun release() {
        recorder?.cancel()
        recorder = null
    }

    fun renderBoard(state: BoardFeature.State = feature.state.value) {
        val ws = WorkspaceStore.current()
        page.boardScope.text = "WORKSPACE · ${ws.name.uppercase(Locale.US)}"
        paintRecord(state)
        paintStatus(state)
        paintRequirements(state.requirements)
        fillColumn(page.ideaCards, page.ideaEmpty, WorkspaceStore.Column.IDEA)
        fillColumn(page.bugCards, page.bugEmpty, WorkspaceStore.Column.BUG)
        fillColumn(page.solutionCards, page.solutionEmpty, WorkspaceStore.Column.SOLUTION)
        fillColumn(page.completedCards, page.completedEmpty, WorkspaceStore.Column.COMPLETED)
    }

    private fun toggleRecord() {
        val rec = recorder ?: return
        if (rec.isRecording() || feature.state.value.listening) {
            stopAndProcess()
            return
        }
        val mic = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
        if (mic != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission { granted ->
                if (granted) startRecording()
                else {
                    feature.setStatus("Microphone permission is required")
                    Toast.makeText(activity, "Microphone permission is required", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        startRecording()
    }

    private fun startRecording() {
        saveProvider()
        val creds = providerCreds()
        if (creds.key.isBlank()) {
            feature.setStatus("Add an API key in Settings")
            Toast.makeText(activity, "Add an API key in Settings", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            recorder?.start()
            feature.setListening(true)
        } catch (t: Throwable) {
            feature.setListening(false)
            val msg = t.message ?: "Could not start the microphone"
            feature.setStatus(msg)
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndProcess() {
        val file = try {
            recorder?.stop()
        } catch (t: Throwable) {
            feature.setListening(false)
            val msg = t.message ?: "Could not stop recording"
            feature.setStatus(msg)
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
        feature.setListening(false)
        if (file == null) {
            feature.setStatus("Nothing recorded")
            Toast.makeText(activity, "Nothing recorded", Toast.LENGTH_SHORT).show()
            return
        }
        saveProvider()
        feature.processRecording(file, providerCreds())
    }

    private fun paintRecord(state: BoardFeature.State) {
        val button = page.boardRecord
        BusyUi.setEnabled(button, !state.drafting)
        if (state.listening) {
            button.text = "STOP"
            button.setTextColor(activity.getColor(R.color.meter_red))
            button.iconTint = ContextCompat.getColorStateList(activity, R.color.meter_red)
            button.strokeColor = ContextCompat.getColorStateList(activity, R.color.meter_red)
        } else {
            button.text = "REC"
            button.setTextColor(activity.getColor(R.color.ink))
            button.iconTint = ContextCompat.getColorStateList(activity, R.color.ink)
            button.strokeColor = ContextCompat.getColorStateList(activity, R.color.line)
        }
    }

    private fun paintStatus(state: BoardFeature.State) {
        val line = when {
            state.partial.isNotBlank() && (state.drafting || state.status.startsWith("Heard")) ->
                state.partial
            state.status.isNotBlank() -> state.status
            state.listening -> "Recording…"
            else -> ""
        }
        if (line.isBlank()) {
            page.boardStatus.visibility = View.GONE
            page.boardStatus.text = ""
        } else {
            page.boardStatus.visibility = View.VISIBLE
            page.boardStatus.text = line
            page.boardStatus.setTextColor(
                activity.getColor(
                    if (state.listening) R.color.meter_red else R.color.muted
                )
            )
        }
    }

    private fun paintRequirements(markdown: String) {
        val text = markdown.trim()
        val display = text.ifBlank { "Speak to draft requirements and board cards." }
        if (display == lastRequirements && page.boardRequirements.text?.toString() == display) return
        lastRequirements = display
        page.boardRequirements.text = display
        page.boardRequirements.setTextColor(
            activity.getColor(if (text.isBlank()) R.color.muted else R.color.tape_ink)
        )
        page.boardRequirementsScroll.post {
            page.boardRequirementsScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun fillColumn(parent: LinearLayout, empty: android.widget.TextView, column: WorkspaceStore.Column) {
        parent.removeAllViews()
        val cards = feature.cardsIn(column)
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        val done = column == WorkspaceStore.Column.COMPLETED
        for (card in cards) {
            val row = RowCardBinding.inflate(inflate, parent, false)
            row.cardTitle.text = card.title
            if (card.body.isBlank()) {
                row.cardBody.visibility = View.GONE
            } else {
                row.cardBody.visibility = View.VISIBLE
                row.cardBody.text = card.body
            }
            if (done) {
                row.root.setOnClickListener { showCardMenu(card) }
            } else {
                row.root.setOnClickListener { buildCard(card) }
                row.root.setOnLongClickListener {
                    showCardMenu(card)
                    true
                }
            }
            parent.addView(row.root)
        }
    }

    private fun showCardMenu(card: WorkspaceStore.Card) {
        val done = card.column == WorkspaceStore.Column.COMPLETED.id
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (!done) {
            labels.add("Build")
            actions.add { buildCard(card) }
        }
        labels.add("Edit")
        actions.add { editCard(card, card.column) }
        for (column in WorkspaceStore.Column.entries) {
            if (column.id == card.column) continue
            labels.add("Move to ${column.label}")
            actions.add {
                feature.moveCard(card.id, column.id)
                renderBoard()
            }
        }
        labels.add("Delete")
        actions.add {
            feature.deleteCard(card.id)
            renderBoard()
        }
        AlertDialog.Builder(activity)
            .setTitle(card.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun buildCard(card: WorkspaceStore.Card) {
        feature.refresh()
        if (feature.state.value.agentBusy) {
            Toast.makeText(activity, "Wait for the agent to finish", Toast.LENGTH_SHORT).show()
            return
        }
        if (card.column != WorkspaceStore.Column.COMPLETED.id) {
            feature.moveCard(card.id, WorkspaceStore.Column.COMPLETED.id)
            renderBoard()
        }
        startVibeFromBoard(feature.instructionFor(card))
    }

    private fun editCard(existing: WorkspaceStore.Card?, column: String) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(activity).apply {
            hint = "Title"
            setSingleLine(true)
            setText(existing?.title.orEmpty())
        }
        val body = EditText(activity).apply {
            hint = "Note"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.body.orEmpty())
        }
        val width = LinearLayout.LayoutParams.MATCH_PARENT
        val height = LinearLayout.LayoutParams.WRAP_CONTENT
        layout.addView(title, LinearLayout.LayoutParams(width, height))
        layout.addView(body, LinearLayout.LayoutParams(width, height))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(WorkspaceStore.Column.from(column).label)
            .setView(layout)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = title.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    title.error = "Add a title"
                    return@setOnClickListener
                }
                val note = body.text?.toString()?.trim().orEmpty()
                val saved = if (existing == null) {
                    WorkspaceStore.addCard(column, text, note)
                } else {
                    WorkspaceStore.updateCard(existing.id, text, note)
                    true
                }
                if (!saved) {
                    title.error = "Board is full"
                    return@setOnClickListener
                }
                feature.refresh()
                dialog.dismiss()
                renderBoard()
            }
        }
        dialog.show()
    }
}
