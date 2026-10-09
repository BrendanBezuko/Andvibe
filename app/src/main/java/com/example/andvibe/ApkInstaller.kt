package com.example.andvibe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sideload an APK via [PackageInstaller] sessions.
 *
 * Hardened builds (GrapheneOS and similar) often expose no activity for
 * `ACTION_VIEW` + `application/vnd.android.package-archive`, so the old
 * FileProvider intent never reaches an installer UI.
 */
object ApkInstaller {
    private const val ACTION_STATUS = "com.example.andvibe.INSTALL_STATUS"
    private const val EXTRA_LABEL = "label"

    /**
     * @return null on success (session committed / settings opened), or an error message.
     */
    fun install(context: Context, apk: File, label: String = apk.name): String? {
        if (!apk.isFile) return "APK is missing: ${apk.name}"
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return "Allow AndVibe to install apps, then try again."
        }
        return try {
            commitSession(context, apk, label)
            null
        } catch (t: Throwable) {
            DebugLog.step("install", "session failed: ${t.message}; falling back to VIEW")
            try {
                fallbackView(context, apk)
                null
            } catch (fallback: Throwable) {
                fallback.message ?: fallback.javaClass.simpleName
            }
        }
    }

    private fun commitSession(context: Context, apk: File, label: String) {
        val app = context.applicationContext
        ensureReceiver(app)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 34) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(ACTION_STATUS).setPackage(app.packageName).putExtra(EXTRA_LABEL, label)
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(app, sessionId, status, flags)
            session.commit(pi.intentSender)
        }
        DebugLog.step("install", "session=$sessionId bytes=${apk.length()} label=$label")
    }

    private fun fallbackView(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Opens the system uninstall UI for [packageName] (needed on signature mismatch). */
    fun promptUninstall(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            DebugLog.step("install", "uninstall prompt failed: ${t.message}")
        }
    }

    @Volatile private var receiverRegistered = false

    private fun ensureReceiver(app: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val filter = IntentFilter(ACTION_STATUS)
            ContextCompat.registerReceiver(
                app,
                StatusReceiver(),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val label = intent.getStringExtra(EXTRA_LABEL) ?: "APK"
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    }
                    DebugLog.step("install", "pending user action for $label")
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    DebugLog.step("install", "success $label")
                    Toast.makeText(context, "$label installed", Toast.LENGTH_SHORT).show()
                }
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                -> {
                    val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                        ?: com.example.andvibe.core.ToolchainPins.COMPANION_PACKAGE
                    DebugLog.step("install", "conflict status=$status pkg=$pkg $message")
                    Toast.makeText(
                        context,
                        "Uninstall the old $label package first, then install again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    promptUninstall(context, pkg)
                }
                else -> {
                    DebugLog.step("install", "fail status=$status $message")
                    val msg = message.orEmpty()
                    if (msg.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                        msg.contains("signatures do not match", ignoreCase = true)
                    ) {
                        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                            ?: com.example.andvibe.core.ToolchainPins.COMPANION_PACKAGE
                        Toast.makeText(
                            context,
                            "Uninstall the old $label package first, then install again.",
                            Toast.LENGTH_LONG,
                        ).show()
                        promptUninstall(context, pkg)
                    } else {
                        Toast.makeText(
                            context,
                            message ?: "Install failed for $label",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
}
