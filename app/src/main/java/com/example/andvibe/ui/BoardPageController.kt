package com.example.andvibe.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.R
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.ColBoardBinding
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
    private val columns = linkedMapOf<CardStatus, ColBoardBinding>()

    fun start() {
        for (status in CardStatus.entries) {
            val col = ColBoardBinding.inflate(inflate, page.boardColumns, false)
            val color = activity.getColor(statusColor(status))
            col.colStripe.setBackgroundColor(color)
            col.colTitle.text = status.label
            col.colTitle.setTextColor(color)
            col.colAdd.setTextColor(color)
            col.colAdd.setOnClickListener { editCard(null, status) }
            page.boardColumns.addView(col.root)
            columns[status] = col
        }
        page.boardRecord.setOnClickListener { toggleRecord() }
        page.boardSync.setOnClickListener { onSyncClick() }
        page.boardSync.setOnLongClickListener {
            projectMenu()
            true
        }
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
                            is BoardFeature.Effect.EditCard -> editCard(effect.card, effect.card.status)
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
        paintSync(state)
        paintStatus(state)
        paintRequirements(state.requirements)
        for ((status, col) in columns) fillColumn(col, status)
    }

    private fun paintSync(state: BoardFeature.State) {
        page.boardSync.text = when {
            state.syncing -> "Syncing…"
            state.project == null -> "GitHub"
            else -> "Sync"
        }
        BusyUi.setEnabled(page.boardSync, !state.syncing && !state.drafting && !state.listening)
    }

    private fun onSyncClick() {
        if (feature.state.value.project == null) linkProject() else feature.sync()
    }

    private fun projectMenu() {
        val project = feature.state.value.project ?: return linkProject()
        val labels = arrayOf("Sync now", "Open project on GitHub", "Change project", "Unlink project")
        AlertDialog.Builder(activity)
            .setTitle(project.url.removePrefix("https://"))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> feature.sync()
                    1 -> openUrl(project.url)
                    2 -> linkProject()
                    3 -> confirm("Unlink GitHub Project?", "Cards stay on the board. Sync links are cleared.") {
                        feature.unlinkProject()
                    }
                }
            }
            .show()
    }

    private fun linkProject() {
        val input = EditText(activity).apply {
            hint = "https://github.com/users/you/projects/1"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(feature.state.value.project?.url.orEmpty())
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Link a GitHub Project")
            .setMessage(
                "Paste the project's URL. Sync uses the GitHub token from Settings; it needs the " +
                    "\"project\" scope. Columns map to the project's Status field; a Type field is added if missing."
            )
            .setView(wrap)
            .setPositiveButton("Link", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (feature.linkProject(input.text?.toString().orEmpty())) {
                    dialog.dismiss()
                    feature.sync()
                } else {
                    input.error = "Use github.com/users/…/projects/N or github.com/orgs/…/projects/N"
                }
            }
        }
        dialog.show()
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

    private fun fillColumn(col: ColBoardBinding, status: CardStatus) {
        val parent = col.colCards
        parent.removeAllViews()
        val cards = feature.cardsIn(status)
        col.colEmpty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        col.colCount.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        col.colCount.text = cards.size.toString()
        val done = status == CardStatus.DONE
        for (card in cards) {
            val row = RowCardBinding.inflate(inflate, parent, false)
            row.cardType.text = card.type.label
            row.cardType.setTextColor(activity.getColor(typeColor(card.type)))
            row.cardMeta.text = listOfNotNull(
                card.issue?.let { "#${it.number}" },
                if (card.remote != null) "synced" else null,
            ).joinToString(" · ")
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
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        add(if (card.status == CardStatus.DONE) "Revisit in Vibe" else "Build") { buildCard(card) }
        add("Edit") { editCard(card, card.status) }
        for (status in CardStatus.entries) {
            if (status == card.status) continue
            add("Move to ${status.label}") {
                feature.moveCard(card.id, status)
                renderBoard()
            }
        }
        val issue = card.issue
        if (issue != null) {
            add("Open issue #${issue.number}") { openUrl(issue.url) }
        } else {
            add("Raise GitHub issue") {
                confirm("Raise as GitHub issue?", raiseIssueNote(card)) { feature.raiseIssue(card) }
            }
        }
        add("Delete") {
            feature.deleteCard(card.id)
            renderBoard()
        }
        AlertDialog.Builder(activity)
            .setTitle(card.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun raiseIssueNote(card: WorkspaceStore.Card): String = buildString {
        append("Creates an issue in the open project's GitHub repo")
        if (card.type == CardType.BUG) append(" with the \"bug\" label")
        append(".")
        if (card.remote != null) append(" The synced project item becomes that issue.")
    }

    private fun buildCard(card: WorkspaceStore.Card) {
        feature.refresh()
        if (feature.state.value.agentBusy) {
            Toast.makeText(activity, "Wait for the agent to finish", Toast.LENGTH_SHORT).show()
            return
        }
        feature.buildCard(card)
        renderBoard()
    }

    private fun editCard(existing: WorkspaceStore.Card?, status: CardStatus) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val types = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val selected = existing?.type ?: CardType.FEATURE
        for (type in CardType.entries) {
            types.addView(
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    tag = type
                    text = type.label
                    setTextColor(activity.getColor(typeColor(type)))
                    isChecked = type == selected
                }
            )
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
        layout.addView(types, LinearLayout.LayoutParams(width, height))
        layout.addView(title, LinearLayout.LayoutParams(width, height))
        layout.addView(body, LinearLayout.LayoutParams(width, height))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "New card · ${status.label}" else "Edit card")
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
                val type = types.findViewById<RadioButton>(types.checkedRadioButtonId)?.tag as? CardType
                    ?: CardType.FEATURE
                val saved = if (existing == null) {
                    feature.addCard(type, status, text, note)
                } else {
                    feature.updateCard(existing.id, text, note, type)
                    true
                }
                if (!saved) {
                    title.error = "Board is full"
                    return@setOnClickListener
                }
                dialog.dismiss()
                renderBoard()
            }
        }
        dialog.show()
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(activity, "No app can open $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun statusColor(status: CardStatus): Int = when (status) {
        CardStatus.BACK_BURNER -> R.color.muted
        CardStatus.TODO -> R.color.ink
        CardStatus.IN_PROGRESS -> R.color.accent
        CardStatus.HUMAN_TESTING -> R.color.quote
        CardStatus.DONE -> R.color.bid
    }

    private fun typeColor(type: CardType): Int = when (type) {
        CardType.FEATURE -> R.color.accent
        CardType.BUG -> R.color.ask
        CardType.SOLUTION -> R.color.bid
    }
}
