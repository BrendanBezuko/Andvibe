package com.example.andvibe.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
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
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.SecretFields
import com.example.andvibe.SecretStore
import com.example.andvibe.databinding.PageSettingsBinding
import com.example.andvibe.features.settings.SettingsFeature

/**
 * Settings overlay: MCP, background permissions, API keys, prompts, and global git identity.
 * Per-project origin lives on the Git tab.
 *
 * Stored secrets are write-only in the UI: fields never reload plaintext; blank keeps existing.
 */
class SettingsPageController(
    private val activity: AppCompatActivity,
    private val page: PageSettingsBinding,
    private val feature: SettingsFeature,
    private val secrets: SecretStore,
    private val openSettingsButton: View,
    private val color: (Int) -> Int,
    private val dp: (Int) -> Int,
    private val requestBackground: () -> Unit,
    private val batteryExempt: () -> Boolean,
    private val closeWorkspaceIfOpen: () -> Unit,
    private val syncBack: () -> Unit,
    private val log: (String) -> Unit,
    private val syncVaultKey: (Provider) -> Unit,
) {
    private val apiKeys = linkedMapOf<Provider, EditText>()
    private val customApiKeys = linkedMapOf<String, EditText>()
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
        page.gitSetupGuide.setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_SETUP_URL)))
        }
        page.saveGit.setOnClickListener { saveGitSettings() }
        page.saveApiKeys.setOnClickListener { saveApiKeys(announce = true) }
        page.savePrompts.setOnClickListener { savePrompts(announce = true) }
        page.resetPrompts.setOnClickListener { resetAllPrompts() }
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
        if (apiKeys.isEmpty() && customApiKeys.isEmpty()) return
        for ((provider, field) in apiKeys) {
            if (provider == Provider.CUSTOM) continue
            val existing = secrets.get(provider, "key", "")
            val key = SecretFields.nextSecret(field.text?.toString().orEmpty(), existing)
            secrets.saveKey(provider, key)
            syncVaultKey(provider)
        }
        for ((id, field) in customApiKeys) {
            val server = secrets.customServers().firstOrNull { it.id == id } ?: continue
            val key = SecretFields.nextSecret(field.text?.toString().orEmpty(), server.key)
            secrets.upsertCustomServer(server.copy(key = key), select = false)
        }
        syncVaultKey(Provider.CUSTOM)
        buildApiKeyFields()
        if (announce) page.keyNote.text = "Saved."
    }

    /** Refresh write-only key field after Console (or elsewhere) updates storage. */
    fun setApiKeyField(provider: Provider) {
        if (provider == Provider.CUSTOM) {
            buildApiKeyFields()
            return
        }
        val field = apiKeys[provider] ?: return
        val emptyHint = "${provider.label} API key"
        val hasStored = secrets.get(provider, "key", "").isNotBlank()
        field.setText("")
        field.hint = SecretFields.hint(emptyHint, hasStored)
    }

    fun setGitFields(
        name: String? = null,
        email: String? = null,
        user: String? = null,
        refreshSecrets: Boolean = false,
    ) {
        name?.let { page.gitName.setText(it) }
        email?.let { page.gitEmail.setText(it) }
        user?.let { page.gitHttpsUser.setText(it) }
        if (refreshSecrets) applyGitSecretFields()
    }

    private fun buildApiKeyFields() {
        val parent = page.apiKeyList
        parent.removeAllViews()
        apiKeys.clear()
        customApiKeys.clear()
        for (provider in Provider.entries) {
            if (provider == Provider.CUSTOM) continue
            val stored = secrets.get(provider, "key", "")
            addKeyField(
                parent,
                key = "${provider.id}_key",
                emptyHint = "${provider.label} API key",
                hasStored = stored.isNotBlank(),
            ) { field -> apiKeys[provider] = field }
        }
        val note = TextView(activity).apply {
            text = "Custom servers (add/edit on Vibe → Model)"
            setTextColor(color(R.color.muted))
            textSize = 12f
        }
        val noteParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        noteParams.topMargin = dp(16)
        parent.addView(note, noteParams)
        secrets.ensureCustomServer()
        for (server in secrets.customServers()) {
            addKeyField(
                parent,
                key = "custom:${server.name}_key",
                emptyHint = "${server.name} API key",
                hasStored = server.key.isNotBlank(),
            ) { field -> customApiKeys[server.id] = field }
        }
    }

    private fun addKeyField(
        parent: LinearLayout,
        key: String,
        emptyHint: String,
        hasStored: Boolean,
        storeField: (EditText) -> Unit,
    ) {
        val label = TextView(activity).apply {
            text = key
            setTextColor(color(R.color.muted))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        labelParams.topMargin = dp(12)
        parent.addView(label, labelParams)

        val field = EditText(activity).apply {
            hint = SecretFields.hint(emptyHint, hasStored)
            setHintTextColor(color(R.color.muted))
            setTextColor(color(R.color.ink))
            setBackgroundResource(R.drawable.bg_field)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 14f
            setText("")
        }
        storeField(field)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(4)
        parent.addView(field, params)
    }

    private fun loadApiKeys() {
        buildApiKeyFields()
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
        applyGitSecretFields()
        page.gitSettingsNote.text = ""
    }

    private fun applyGitSecretFields() {
        page.gitHttpsToken.setText("")
        page.gitHttpsToken.hint = SecretFields.hint(
            "GitHub token (PAT)",
            secrets.gitToken().isNotBlank(),
        )
        page.gitSsh.setText("")
        page.gitSsh.hint = SecretFields.hint(
            "SSH private key (optional)",
            secrets.gitSsh().isNotBlank(),
        )
    }

    private fun saveGitSettings() {
        val name = page.gitName.text?.toString()?.trim().orEmpty().ifBlank { "AndVibe" }
        val email = page.gitEmail.text?.toString()?.trim().orEmpty().ifBlank { "andvibe@local" }
        val user = page.gitHttpsUser.text?.toString()?.trim().orEmpty()
        val token = SecretFields.nextSecret(
            page.gitHttpsToken.text?.toString().orEmpty(),
            secrets.gitToken(),
        )
        val ssh = SecretFields.nextSecret(
            page.gitSsh.text?.toString().orEmpty(),
            secrets.gitSsh(),
        )
        secrets.saveGit(name, email, user, token, ssh)
        applyGitSecretFields()
        val note = "Saved git identity."
        page.gitSettingsNote.text = note
        log(note)
    }

    companion object {
        private const val GITHUB_SETUP_URL = "https://andvibe.org/github/"
    }
}
