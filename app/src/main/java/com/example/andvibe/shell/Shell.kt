package com.example.andvibe.shell

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.example.andvibe.Tab
import com.example.andvibe.features.FeatureEffects
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** UI capability registered by MainActivity in onStart / cleared in onStop. */
interface ScreenshotSource {
    suspend fun capture(tab: Tab?): Bitmap?
}

sealed interface ShellEffect {
    data object LogChanged : ShellEffect
    data object FilesChanged : ShellEffect
    data object ProjectChanged : ShellEffect
    data object BuildChanged : ShellEffect
    data object BusyChanged : ShellEffect
    data object McpChanged : ShellEffect
    data object UsageChanged : ShellEffect
    data class OpenFile(val file: File) : ShellEffect
    data class PreviewFile(val file: File) : ShellEffect
}

/**
 * Cross-cutting shell notifications (replaces the old static UI listener bus). Features post effects;
 * MainActivity collects and forwards to controllers.
 */
class Shell {
    private val effects = FeatureEffects<ShellEffect>()
    val effectsFlow: Flow<ShellEffect> = effects.events
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var screenshotSource: ScreenshotSource? = null

    fun emit(effect: ShellEffect) {
        // Post to main so collectors started on the UI lifecycle see updates reliably
        // when emitters run off the main thread (repo/agent workers).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            effects.tryEmit(effect)
        } else {
            main.post { effects.tryEmit(effect) }
        }
    }

    fun logChanged() = emit(ShellEffect.LogChanged)
    fun filesChanged() = emit(ShellEffect.FilesChanged)
    fun projectChanged() = emit(ShellEffect.ProjectChanged)
    fun buildChanged() = emit(ShellEffect.BuildChanged)
    fun busyChanged() = emit(ShellEffect.BusyChanged)
    fun mcpChanged() = emit(ShellEffect.McpChanged)
    fun usageChanged() = emit(ShellEffect.UsageChanged)
    fun open(file: File) = emit(ShellEffect.OpenFile(file))
    fun preview(file: File) = emit(ShellEffect.PreviewFile(file))

    /** Blocks the caller (MCP worker). Never call from the main thread. */
    fun screenshot(tab: Tab?, timeoutMs: Long = 8_000): Bitmap? {
        val source = screenshotSource ?: return null
        return runBlocking {
            withTimeoutOrNull(timeoutMs) { source.capture(tab) }
                ?: error("screenshot timed out")
        }
    }
}
