package com.example.andvibe

import android.content.Context
import android.system.Os
import com.example.andvibe.core.ToolchainPins
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Run Termux `gh` / `ssh` from the Build Tools companion APK.
 *
 * Binaries live as `lib*bin.so` under the companion [nativeLibraryDir] (W^X).
 * Symlinks named `gh` / `ssh` under a staged bin dir go on PATH so `gh` can
 * find `ssh`. [GH_TOKEN] comes from the HTTPS token in Settings; the SSH
 * private key field is written to `$HOME/.ssh` for openssh.
 */
object CompanionCli {
    private const val TIMEOUT_MINUTES = 10L

    data class Result(val code: Int, val output: String)

    fun ready(context: Context): Boolean {
        val native = ToolchainExec.companionNativeDir(context) ?: return false
        return File(native, ToolchainPins.GH_LIB).isFile &&
            File(native, ToolchainPins.SSH_LIB).isFile
    }

    /**
     * Stage PATH symlinks + SSH home, then run [argv] (e.g. `listOf("gh", "auth", "status")`).
     */
    fun run(
        context: Context,
        secrets: SecretStore,
        argv: List<String>,
        cwd: File,
        log: (String) -> Unit = {},
    ): Result {
        if (argv.isEmpty()) error("empty command")
        if (!ready(context)) {
            error("Install AndVibe Build Tools (Build tab) for gh/openssh, then try again.")
        }
        val native = ToolchainExec.companionNativeDir(context)
            ?: error("Build Tools companion not installed")
        val home = GitIdentity.home(context)
        val bin = stageBinDir(context, native, log)
        ensureSshHome(home, secrets, log)

        val pb = ProcessBuilder(argv)
        pb.directory(cwd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["LD_LIBRARY_PATH"] = native.absolutePath
        env["PATH"] = "${bin.absolutePath}:/system/bin:/system/xbin"
        env["TMPDIR"] = File(context.cacheDir, "cli-tmp").also { it.mkdirs() }.absolutePath
        // Prefer our known_hosts / identity; Termux openssh may probe a hardcoded prefix.
        env["GIT_SSH_COMMAND"] = "${File(bin, "ssh").absolutePath} -o BatchMode=yes"
        val token = secrets.gitToken().trim()
        if (token.isNotEmpty()) {
            env["GH_TOKEN"] = token
            env["GITHUB_TOKEN"] = token
        }

        log("+ ${argv.joinToString(" ")}")
        val proc = pb.start()
        val output = StringBuilder()
        proc.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                output.append(line).append('\n')
                log(line)
            }
        }
        val finished = proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            error("timed out after ${TIMEOUT_MINUTES}m: ${argv.first()}")
        }
        return Result(proc.exitValue(), output.toString().trimEnd())
    }

    fun runGh(
        context: Context,
        secrets: SecretStore,
        args: List<String>,
        cwd: File,
        log: (String) -> Unit = {},
    ): Result {
        if (args.isEmpty()) {
            error("usage: gh <command>…  Authenticated calls use the HTTPS token from Settings as GH_TOKEN.")
        }
        val native = ToolchainExec.companionNativeDir(context)
            ?: error("Install AndVibe Build Tools first")
        val gh = File(native, ToolchainPins.GH_LIB)
        if (!gh.isFile) error("gh missing from Build Tools — tap Reinstall Build Tools")
        if (secrets.gitToken().isBlank()) {
            log("note: no HTTPS token in Settings — GH_TOKEN unset (public/read-only only)")
        }
        return run(context, secrets, listOf(gh.absolutePath) + args, cwd, log)
    }

    /** Symlink companion libs to plain names (`gh`, `ssh`, …) for PATH lookup. */
    fun stageBinDir(context: Context, native: File, log: (String) -> Unit = {}): File {
        val bin = File(context.filesDir, "cli-bin").also { it.mkdirs() }
        val links = listOf(
            "gh" to ToolchainPins.GH_LIB,
            "ssh" to ToolchainPins.SSH_LIB,
            "ssh-keygen" to ToolchainPins.SSH_KEYGEN_LIB,
            "scp" to ToolchainPins.SCP_LIB,
        )
        for ((name, lib) in links) {
            val target = File(native, lib)
            if (!target.isFile) continue
            symlinkReplace(File(bin, name), target)
        }
        log("cli bin → ${bin.absolutePath}")
        return bin
    }

    /** Write Settings SSH key + config under [home]/.ssh for openssh and JGit. */
    fun ensureSshHome(home: File, secrets: SecretStore, log: (String) -> Unit = {}) {
        val sshDir = File(home, ".ssh").also { it.mkdirs() }
        try {
            Os.chmod(sshDir.absolutePath, 448) // 0700
        } catch (_: Throwable) {
            sshDir.setReadable(false, false)
            sshDir.setReadable(true, true)
            sshDir.setWritable(false, false)
            sshDir.setWritable(true, true)
            sshDir.setExecutable(false, false)
            sshDir.setExecutable(true, true)
        }

        val keyPem = secrets.gitSsh().trim()
        val keyFile = File(sshDir, "id_andvibe")
        if (keyPem.isNotEmpty()) {
            val body = if (keyPem.contains("BEGIN") && !keyPem.endsWith("\n")) {
                keyPem + "\n"
            } else {
                keyPem
            }
            keyFile.writeText(body)
            try {
                Os.chmod(keyFile.absolutePath, 384) // 0600
            } catch (_: Throwable) {
                keyFile.setReadable(false, false)
                keyFile.setReadable(true, true)
                keyFile.setWritable(false, false)
                keyFile.setWritable(true, true)
            }
            log("SSH key staged (${keyFile.length()} bytes)")
        } else if (keyFile.exists()) {
            keyFile.delete()
        }

        val config = File(sshDir, "config")
        val lines = mutableListOf(
            "Host *",
            "  UserKnownHostsFile ${File(sshDir, "known_hosts").absolutePath}",
            "  GlobalKnownHostsFile /dev/null",
            "  StrictHostKeyChecking accept-new",
        )
        if (keyFile.isFile && keyFile.length() > 0) {
            lines += "  IdentityFile ${keyFile.absolutePath}"
            lines += "  IdentitiesOnly yes"
        }
        config.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun symlinkReplace(link: File, target: File) {
        link.parentFile?.mkdirs()
        try {
            java.nio.file.Files.deleteIfExists(link.toPath())
        } catch (_: Throwable) {
            link.delete()
        }
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (_: Throwable) {
            // Last resort: copy (wastes space; still W^X if we copy into filesDir — may fail exec).
            // Prefer failing PATH lookup over a non-exec copy.
        }
    }
}
