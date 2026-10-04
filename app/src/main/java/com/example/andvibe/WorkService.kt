package com.example.andvibe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class WorkService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        starting = false
        wake = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndVibe:work")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_MS)
            }
        DebugLog.step("service", "create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = Jobs.active()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notify.WORKING_ID, Notify.working(this, jobs), type)
        } catch (t: Throwable) {
            DebugLog.step("service", "foreground refused ${t.javaClass.simpleName}: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (jobs.isEmpty()) finish()
        return START_NOT_STICKY
    }

    // Android 15+ caps dataSync services at 6 hours a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        DebugLog.step("service", "timeout type=$fgsType")
        finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        starting = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        DebugLog.step("service", "destroy")
        super.onDestroy()
    }

    private fun show(jobs: List<Jobs.Job>) {
        Notify.update(this, Notify.WORKING_ID, Notify.working(this, jobs))
        wake?.acquire(WAKE_MS)
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val WAKE_MS = 3 * 60 * 60 * 1000L

        private var current: WorkService? = null
        private var starting = false

        // Main thread only.
        fun sync(context: Context) {
            val jobs = Jobs.active()
            val service = current
            when {
                jobs.isEmpty() -> service?.finish()
                service != null -> service.show(jobs)
                !starting -> {
                    starting = true
                    try {
                        ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java))
                    } catch (t: Throwable) {
                        starting = false
                        DebugLog.step("service", "start refused ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }
    }
}
