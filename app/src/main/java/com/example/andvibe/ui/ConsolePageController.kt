package com.example.andvibe.ui

import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.ProjectSession
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.SecretStore
import com.example.andvibe.core.GitOps
import com.example.andvibe.databinding.PageConsoleBinding
import com.example.andvibe.features.console.ConsoleFeature
import com.example.andvibe.features.console.ConsoleLog
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Renders console tape + Secrets/Variables vault. Forwards commands to [ConsoleFeature].
 */
class ConsolePageController(
    private val page: PageConsoleBinding,
    private val feature: ConsoleFeature,
    private val log: ConsoleLog,
    private val session: ProjectSession,
    private val store: SecretStore,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val repoDispatcher: CoroutineDispatcher,
    private val color: (Int) -> Int,
    private val dp: (Int) -> Int,
    private val currentProvider: () -> Provider,
    private val saveProvider: () -> Unit,
    private val onSecretsSynced: (gitToken: String, ssh: String) -> Unit,
    private val onVariablesSynced: (
        provider: Provider,
        model: String,
        base: String,
        gitName: String,
        gitEmail: String,
        gitUser: String,
        origin: String,
    ) -> Unit,
    private val syncApiKeyField: (Provider, String) -> Unit,
    private val paintBusy: () -> Unit,
    private val runOnUi: ( () -> Unit) -> Unit,
) {
    private val vault = mutableMapOf<String, EditText>()

    fun start() {
        page.commandRun.setOnClickListener { submitCommand() }
        page.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCommand()
                true
            } else {
                false
            }
        }
        page.consoleTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showConsoleTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        page.saveSecrets.setOnClickListener { saveSecrets() }
        page.saveVariables.setOnClickListener { saveVariables() }
        buildVault()

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    log.revision.collect { onLog() }
                }
                launch {
                    feature.state.collect { paintConsoleBusy(it.running) }
                }
            }
        }
    }

    fun onLog() {
        val scroll = page.logScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        page.logView.text = paintLog(log.text())
        paintTape()
        if (nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun paintTape() {
        val project = session.selectedRoot()?.name ?: "—"
        page.consoleProject.text = project
        page.consoleClock.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        paintConsoleBusy(feature.state.value.running)
    }

    fun paintBusy() {
        paintConsoleBusy(feature.state.value.running)
    }

    fun loadVault() {
        for (provider in Provider.entries) {
            vault["${provider.id}_key"]?.setText(store.get(provider, "key", ""))
            vault["${provider.id}_model"]?.setText(store.get(provider, "model", provider.defaultModel))
            vault["${provider.id}_base"]?.setText(store.get(provider, "base", provider.defaultBase))
        }
        vault["build_token"]?.setText(store.buildToken())
        vault["build_url"]?.setText(store.buildUrl())
        vault["prefer_remote_build"]?.setText(if (store.preferRemoteBuild()) "true" else "false")
        vault["git_token"]?.setText(store.gitToken())
        vault["git_ssh"]?.setText(store.gitSsh())
        vault["git_name"]?.setText(store.gitName())
        vault["git_email"]?.setText(store.gitEmail())
        vault["git_user"]?.setText(store.gitUser())
        vault["git_origin"]?.setText(
            runCatching { GitOps.originUrl(session.cwd, session.reposDir) }.getOrDefault("")
        )
    }

    fun setVaultKey(provider: Provider, key: String) {
        vault["${provider.id}_key"]?.setText(key)
    }

    private fun paintConsoleBusy(running: Boolean) {
        BusyUi.setEnabled(page.commandRun, !running)
        BusyUi.setEnabled(page.commandInput, !running)
    }

    private fun paintLog(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
        val span = SpannableString(raw)
        val normal = color(R.color.tape_ink)
        val bid = color(R.color.bid)
        val ask = color(R.color.ask)
        val quote = color(R.color.quote)
        var start = 0
        while (start <= raw.length) {
            val newline = raw.indexOf('\n', start)
            val end = if (newline < 0) raw.length else newline
            if (end > start) {
                val line = raw.substring(start, end)
                val lineColor = when {
                    line.startsWith("$ ") -> quote
                    line.startsWith("error", ignoreCase = true) ||
                        line.contains("failed", ignoreCase = true) -> ask
                    line.startsWith("saved") || line.startsWith("staged") ||
                        line.startsWith("unstaged") || line.startsWith("tagged") ||
                        line.startsWith("pushed") || line.startsWith("pulled") -> bid
                    else -> normal
                }
                span.setSpan(ForegroundColorSpan(lineColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (newline < 0) break
            start = newline + 1
        }
        return span
    }

    private fun showConsoleTab(index: Int) {
        page.terminalPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        page.secretsPane.visibility = if (index == 1) View.VISIBLE else View.GONE
        page.variablesPane.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index != 0) {
            saveProvider()
            loadVault()
        }
    }

    private fun buildVault() {
        val secrets = page.secretsList
        val variables = page.variablesList
        vaultNote(secrets, "API keys, the build token, and git credentials. They stay encrypted on this device.")
        for (provider in Provider.entries) {
            vaultField(secrets, "${provider.id}_key", "${provider.label} API key", secret = true)
        }
        vaultField(secrets, "build_token", "Build token", secret = true)
        vaultField(secrets, "git_token", "Git HTTPS token", secret = true)
        vaultField(secrets, "git_ssh", "SSH private key", secret = true, lines = 4)

        vaultNote(variables, "Models, base URLs, optional remote builder, and git identity. Gradle builds on-device by default.")
        for (provider in Provider.entries) {
            vaultField(variables, "${provider.id}_model", "${provider.label} model", secret = false)
            vaultField(variables, "${provider.id}_base", "${provider.label} base URL", secret = false)
        }
        vaultField(variables, "build_url", "Remote builder URL (optional)", secret = false)
        vaultField(variables, "prefer_remote_build", "Prefer remote builder (true/false)", secret = false)
        vaultField(variables, "git_name", "Git name", secret = false)
        vaultField(variables, "git_email", "Git email", secret = false)
        vaultField(variables, "git_user", "Git HTTPS user", secret = false)
        vaultField(variables, "git_origin", "Remote URL", secret = false)
        loadVault()
    }

    private fun vaultNote(parent: LinearLayout, text: String) {
        parent.addView(TextView(parent.context).apply {
            this.text = text
            setTextColor(color(R.color.muted))
            textSize = 13f
        })
    }

    private fun vaultField(parent: LinearLayout, key: String, hint: String, secret: Boolean, lines: Int = 1) {
        val field = EditText(parent.context).apply {
            this.hint = hint
            setHintTextColor(color(R.color.muted))
            setTextColor(color(R.color.ink))
            setBackgroundResource(R.drawable.bg_field)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 14f
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(8)
        parent.addView(field, params)
        vault[key] = field
    }

    private fun saveSecrets() {
        val selected = currentProvider()
        for (provider in Provider.entries) {
            val key = vaultText("${provider.id}_key")
            store.saveProvider(
                provider,
                key,
                store.get(provider, "model", provider.defaultModel),
                store.get(provider, "base", provider.defaultBase),
                select = provider == selected
            )
            syncApiKeyField(provider, key)
        }
        val token = vaultText("build_token")
        val gitToken = vaultText("git_token")
        val ssh = vaultText("git_ssh")
        store.saveBuild(store.buildUrl(), token)
        store.saveGit(store.gitName(), store.gitEmail(), store.gitUser(), gitToken, ssh)
        onSecretsSynced(gitToken, ssh)
        log.append("saved secrets")
        feature.refreshLog()
        onLog()
    }

    private fun saveVariables() {
        val selected = currentProvider()
        for (provider in Provider.entries) {
            store.saveProvider(
                provider,
                store.get(provider, "key", ""),
                vaultText("${provider.id}_model"),
                vaultText("${provider.id}_base"),
                select = provider == selected
            )
        }
        val url = vaultText("build_url")
        val preferRemote = vaultText("prefer_remote_build").equals("true", ignoreCase = true)
        val name = vaultText("git_name")
        val email = vaultText("git_email")
        val user = vaultText("git_user")
        val origin = vaultText("git_origin")
        store.saveBuild(url, store.buildToken())
        store.setPreferRemoteBuild(preferRemote)
        store.saveGit(name, email, user, store.gitToken(), store.gitSsh())
        onVariablesSynced(
            selected,
            vaultText("${selected.id}_model"),
            vaultText("${selected.id}_base"),
            name,
            email,
            user,
            origin,
        )
        scope.launch(repoDispatcher) {
            val current = runCatching { GitOps.originUrl(session.cwd, session.reposDir) }.getOrDefault("")
            val message = if (origin == current) {
                "saved variables"
            } else {
                GitOps.setOrigin(session.cwd, session.reposDir, origin)
            }
            runOnUi {
                log.append(message)
                feature.refreshLog()
                onLog()
            }
        }
    }

    private fun vaultText(key: String): String = vault[key]?.text?.toString()?.trim().orEmpty()

    private fun submitCommand() {
        val line = page.commandInput.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        page.commandInput.setText("")
        feature.submit(line)
        paintBusy()
    }
}
