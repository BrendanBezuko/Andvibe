package com.example.andvibe.ui

import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.PageBoardBinding
import com.example.andvibe.databinding.RowCardBinding
import com.example.andvibe.features.board.BoardFeature
import java.util.Locale

/**
 * Renders the Board tab columns and card dialogs. Build→Vibe goes through [startVibeFromBoard].
 */
class BoardPageController(
    private val activity: AppCompatActivity,
    private val page: PageBoardBinding,
    private val feature: BoardFeature,
    private val inflate: LayoutInflater,
    private val startVibeFromBoard: (String) -> Unit,
) {
    fun start() {
        page.addIdea.setOnClickListener { editCard(null, WorkspaceStore.Column.IDEA.id) }
        page.addBug.setOnClickListener { editCard(null, WorkspaceStore.Column.BUG.id) }
        page.addSolution.setOnClickListener { editCard(null, WorkspaceStore.Column.SOLUTION.id) }
        page.addCompleted.setOnClickListener { editCard(null, WorkspaceStore.Column.COMPLETED.id) }
    }

    fun renderBoard() {
        val ws = WorkspaceStore.current()
        page.boardScope.text = "WORKSPACE · ${ws.name.uppercase(Locale.US)}"
        fillColumn(page.ideaCards, page.ideaEmpty, WorkspaceStore.Column.IDEA)
        fillColumn(page.bugCards, page.bugEmpty, WorkspaceStore.Column.BUG)
        fillColumn(page.solutionCards, page.solutionEmpty, WorkspaceStore.Column.SOLUTION)
        fillColumn(page.completedCards, page.completedEmpty, WorkspaceStore.Column.COMPLETED)
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
