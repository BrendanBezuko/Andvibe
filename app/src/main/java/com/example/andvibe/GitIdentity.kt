package com.example.andvibe

import android.content.Context
import com.example.andvibe.core.GitOps
import java.io.File
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder

/**
 * Stages git identity files (SSH key under cli-home) and wires JGit transports.
 * HTTPS uses the PAT from Settings; SSH remotes use the private key field.
 */
object GitIdentity {
    fun home(context: Context): File =
        File(context.applicationContext.filesDir, "cli-home").also { it.mkdirs() }

    fun ensureSshHome(context: Context, secrets: SecretStore, log: (String) -> Unit = {}) {
        CompanionCli.ensureSshHome(home(context), secrets, log)
    }

    /** Call from [AppGraph] so every [GitOps] network op uses current Settings. */
    fun install(context: Context, secrets: SecretStore) {
        val app = context.applicationContext
        GitOps.auth = {
            GitOps.Auth(
                name = secrets.gitName().ifBlank { "AndVibe" },
                email = secrets.gitEmail().ifBlank { "andvibe@local" },
                user = secrets.gitUser(),
                token = secrets.gitToken(),
                sshKey = secrets.gitSsh(),
            )
        }
        GitOps.prepareTransport = {
            HttpTransport.setConnectionFactory(JDKHttpConnectionFactory())
            ensureSshHome(app, secrets)
            val home = home(app)
            val sshDir = File(home, ".ssh")
            val key = File(sshDir, "id_andvibe")
            if (key.isFile && key.length() > 0) {
                val factory = SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(home)
                    .setSshDirectory(sshDir)
                    .setDefaultIdentities { listOf(key.toPath()) }
                    .build(null)
                SshSessionFactory.setInstance(factory)
            }
        }
    }

    /** owner/repo from a GitHub https or ssh origin, or blank. */
    fun githubSlug(origin: String): String {
        val raw = origin.trim().removeSuffix(".git")
        val https = Regex("""(?i)https?://(?:www\.)?github\.com/([^/]+)/([^/]+)""").find(raw)
        if (https != null) return "${https.groupValues[1]}/${https.groupValues[2]}"
        val ssh = Regex("""(?i)(?:git@github\.com:|ssh://git@github\.com/)([^/]+)/([^/]+)""").find(raw)
        if (ssh != null) return "${ssh.groupValues[1]}/${ssh.groupValues[2]}"
        return ""
    }
}
