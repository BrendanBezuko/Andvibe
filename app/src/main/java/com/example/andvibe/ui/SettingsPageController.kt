package com.example.andvibe.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.andvibe.DebugMcp
import com.example.andvibe.Notify
import com.example.andvibe.ProjectSession
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.SecretStore
import com.example.andvibe.Tab
import com.example.andvibe.core.GitOps
import com.example.andvibe.databinding.PageSettingsBinding
import com.example.andvibe.features.settings.SettingsFeature

/**
 * Settings overlay: MCP, background permissions, API keys, prompts, and git credentials.
 * Permission launchers stay in the Activity and are passed in as [requestBackground].
 */
class SettingsPageController(
    private val activity: AppCompatActivity,
    private val page: PageSettingsBinding,
    private val feature: SettingsFeature,
    private val secrets: SecretStore,
    private val session: ProjectSession,
    private val openSettingsButton: View,
    private val color: (Int) -> Int,
    private val dp: (Int) -> Int,
    private val requestBackground: () -> Unit,
    private val batteryExempt: () -> Boolean,
    private val closeWorkspaceIfOpen: () -> Unit,
    private val syncBack: () -> Unit,
    private val log: (String) -> Unit,
    private val currentTab: () -> Tab,
    private val refreshGitIfVisible: () -> Unit,
    private val syncVaultKey: (Provider, String) -> Unit,
) {
    private val apiKeys = linkedMapOf<Provider, EditText>()
    private val promptFields = linkedMapOf<PromptStore.Kind, EditText>()

    var isOpen: Boolean = false
        private set

    fun start() {
        openSettingsButton.setOnClickListener { open() }
        page.mcpRetry.setOnClickListener {
            DebugMcp.start()
            renderMcp()
        }
        renderMcp()
        page.mcpEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked != DebugMcp.isEnabled()) feature.setMcpEnabled(checked)
            renderMcp()
        }
        page.allowBackground.setOnClickListener { requestBackground() }
        page.saveGit.setOnClickListener { saveGitSettings() }
        page.saveApiKeys.setOnClickListener { saveApiKeys(announce = true) }
        page.savePrompts.setOnClickListener { savePrompts(announce = true) }
        page.resetPrompts.setOnClickListener { resetAllPrompts() }
        page.showApiKeys.setOnCheckedChangeListener { _, checked ->
            val type = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            for (field in apiKeys.values) {
                field.inputType = type
                field.setSelection(field.text?.length ?: 0)
            }
        }
        buildApiKeyFields()
        buildPromptFields()
    }

    fun open() {
        closeWorkspaceIfOpen()
        isOpen = true
        renderMcp()
        renderBackground()
        loadGitSettings()
        loadApiKeys()
        loadPrompts()
        page.root.visibility = View.VISIBLE
        syncBack()
    }

    fun close() {
        if (!isOpen) return
        saveApiKeys(announce = false)
        savePrompts(announce = false)
        isOpen = false
        page.root.visibility = View.GONE
        syncBack()
    }

    fun renderMcp() {
        val on = DebugMcp.isEnabled()
        if (page.mcpEnabled.isChecked != on) page.mcpEnabled.isChecked = on
        page.mcpStatus.text = DebugMcp.statusText()
        page.mcpRetry.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun renderBackground() {
        val notify = if (Notify.allowed(activity)) "on" else "off"
        val battery = if (batteryExempt()) "unrestricted" else "optimized"
        page.backgroundStatus.text = "notifications  $notify\nbattery        $battery"
        page.allowBackground.visibility =
            if (notify == "on" && batteryExempt()) View.GONE else View.VISIBLE
    }

    fun saveApiKeys(announce: Boolean) {
        if (apiKeys.isEmpty()) return
        for ((provider, field) in apiKeys) {
            val key = field.text?.toString()?.trim().orEmpty()
            secrets.saveKey(provider, key)
            syncVaultKey(provider, key)
        }
        if (announce) page.keyNote.text = "Saved."
    }

    fun setApiKeyField(provider: Provider, key: String) {
        apiKeys[provider]?.setText(key)
    }

    fun setGitFields(name: String? = null, email: String? = null, user: String? = null, token: String? = null, ssh: String? = null, origin: String? = null) {
        name?.let { page.gitName.setText(it) }
        email?.let { page.gitEmail.setText(it) }
        user?.let { page.gitHttpsUser.setText(it) }
        token?.let { page.gitHttpsToken.setText(it) }
        ssh?.let { page.gitSsh.setText(it) }
        origin?.let { page.gitOrigin.setText(it) }
    }

    private fun buildApiKeyFields() {
        val parent = page.apiKeyList
        if (parent.childCount > 0) return
        for (provider in Provider.entries) {
            val field = EditText(activity).apply {
                hint = "${provider.label} API key"
                setHintTextColor(color(R.color.muted))
                setTextColor(color(R.color.ink))
                setBackgroundResource(R.drawable.bg_field)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
                setPadding(dp(10), dp(10), dp(10), dp(10))
                textSize = 14f
                setText(secrets.get(provider, "key", ""))
            }
            apiKeys[provider] = field
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = dp(8)
            parent.addView(field, params)
        }
    }

    private fun loadApiKeys() {
        for ((provider, field) in apiKeys) {
            field.setText(secrets.get(provider, "key", ""))
        }
        page.keyNote.text = ""
    }

    private fun buildPromptFields() {
        val parent = page.promptList
        if (parent.childCount > 0) return
        for (kind in PromptStore.Kind.entries) {
            val title = TextView(activity).apply {
                text = kind.label
                setTextColor(color(R.color.ink))
                textSize = 13f
            }
            val titleParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            titleParams.topMargin = dp(16)
            parent.addView(title, titleParams)

            val blurb = TextView(activity).apply {
                text = kind.blurb
                setTextColor(color(R.color.muted))
                textSize = 12f
            }
            val blurbParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            blurbParams.topMargin = dp(4)
            parent.addView(blurb, blurbParams)

            val field = EditText(activity).apply {
                setHintTextColor(color(R.color.muted))
                setTextColor(color(R.color.ink))
                setBackgroundResource(R.drawable.bg_field)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                minLines = if (kind == PromptStore.Kind.COMMIT) 2 else 6
                setPadding(dp(10), dp(10), dp(10), dp(10))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setText(PromptStore.get(kind))
            }
            promptFields[kind] = field
            val fieldParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            fieldParams.topMargin = dp(8)
            parent.addView(field, fieldParams)

            val reset = Button(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Reset"
                textSize = 13f
                minHeight = dp(40)
                setOnClickListener {
                    field.setText(PromptStore.default(kind))
                    PromptStore.reset(kind)
                    page.promptNote.text = "Restored ${kind.label} default."
                }
            }
            val resetParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            resetParams.topMargin = dp(6)
            parent.addView(reset, resetParams)
        }
    }

    private fun loadPrompts() {
        for ((kind, field) in promptFields) {
            field.setText(PromptStore.get(kind))
        }
        page.promptNote.text = ""
    }

    private fun savePrompts(announce: Boolean) {
        if (promptFields.isEmpty()) return
        for ((kind, field) in promptFields) {
            PromptStore.set(kind, field.text?.toString().orEmpty())
        }
        if (announce) page.promptNote.text = "Saved."
    }

    private fun resetAllPrompts() {
        PromptStore.resetAll()
        for ((kind, field) in promptFields) {
            field.setText(PromptStore.default(kind))
        }
        page.promptNote.text = "Restored all defaults."
    }

    private fun loadGitSettings() {
        page.gitName.setText(secrets.gitName().ifBlank { "AndVibe" })
        page.gitEmail.setText(secrets.gitEmail().ifBlank { "andvibe@local" })
        page.gitHttpsUser.setText(secrets.gitUser())
        page.gitHttpsToken.setText(secrets.gitToken())
        page.gitSsh.setText(secrets.gitSsh())
        page.gitOrigin.setText(
            runCatching { GitOps.originUrl(session.cwd, session.reposDir) }.getOrDefault(""),
        )
        page.gitSettingsNote.text = ""
    }

    private fun saveGitSettings() {
        val name = page.gitName.text?.toString()?.trim().orEmpty().ifBlank { "AndVibe" }
        val email = page.gitEmail.text?.toString()?.trim().orEmpty().ifBlank { "andvibe@local" }
        val user = page.gitHttpsUser.text?.toString()?.trim().orEmpty()
        val token = page.gitHttpsToken.text?.toString().orEmpty()
        val ssh = page.gitSsh.text?.toString().orEmpty()
        secrets.saveGit(name, email, user, token, ssh)
        val origin = page.gitOrigin.text?.toString()?.trim().orEmpty()
        val note = if (origin.isEmpty()) {
            "Saved the account."
        } else {
            runCatching { GitOps.setOrigin(session.cwd, session.reposDir, origin) }
                .getOrElse { it.message ?: "could not set origin" }
        }
        page.gitSettingsNote.text = note
        log(note)
        if (currentTab() == Tab.GIT) refreshGitIfVisible()
    }
}
