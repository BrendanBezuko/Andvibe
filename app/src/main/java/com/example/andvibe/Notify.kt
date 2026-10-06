package com.example.andvibe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.example.andvibe.tasks.TaskRunner
import java.io.File

object Notify {
    const val WORKING_ID = 1
    const val EXTRA_TAB = "andvibe.tab"
    private const val WORK = "work"
    private const val DONE = "done"

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(WORK, "Running in the background", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a build, agent, or git job keeps going."
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(DONE, "Finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pings when a build, agent, or git job finishes."
            }
        )
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun working(context: Context, jobs: List<TaskRunner.Task>): Notification {
        val first = jobs.firstOrNull()
        val title = when {
            first == null -> "Finishing up"
            jobs.size == 1 -> first.label
            else -> "${jobs.size} jobs running"
        }
        val text = if (jobs.size > 1) {
            jobs.joinToString(" · ") { it.label }
        } else {
            "Keeps going if you leave AndVibe."
        }
        return NotificationCompat.Builder(context, WORK)
            .setSmallIcon(icon(first?.tab))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(first?.started ?: System.currentTimeMillis())
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, first?.tab ?: AppState.tab, 0))
            .build()
    }

    fun done(context: Context, job: TaskRunner.Task, title: String, text: String, apk: File?) {
        val body = text.trim().ifBlank { job.label }.take(800)
        val requestCode = 1000 + job.id
        val builder = NotificationCompat.Builder(context, DONE)
            .setSmallIcon(icon(job.tab))
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open(context, job.tab, requestCode))
        if (apk != null && apk.isFile) {
            install(context, apk, requestCode)?.let { builder.addAction(0, "Install", it) }
        }
        update(context, requestCode, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, id: Int, notification: Notification) {
        if (!allowed(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (t: SecurityException) {
            DebugLog.step("notify", "blocked ${t.message}")
        }
    }

    private fun open(context: Context, tab: AppState.Tab, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab.name)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun install(context: Context, apk: File, requestCode: Int): PendingIntent? {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode + 500_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun icon(tab: AppState.Tab?): Int = when (tab) {
        AppState.Tab.BUILD -> R.drawable.ic_nav_build
        AppState.Tab.VIBE -> R.drawable.ic_nav_vibe
        AppState.Tab.UNDERSTAND -> R.drawable.ic_nav_understand
        AppState.Tab.GIT -> R.drawable.ic_nav_git
        AppState.Tab.FILES -> R.drawable.ic_nav_files
        AppState.Tab.SEARCH -> R.drawable.ic_nav_search
        AppState.Tab.BOARD -> R.drawable.ic_nav_board
        else -> R.drawable.ic_nav_console
    }
}
