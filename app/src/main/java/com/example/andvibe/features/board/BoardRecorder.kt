package com.example.andvibe.features.board

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Mic capture to a short AAC/M4A clip for provider transcription. */
class BoardRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var recording = false

    fun isRecording(): Boolean = recording

    fun start(): File {
        if (recording) error("already recording")
        val file = File(context.cacheDir, "board-voice-${System.currentTimeMillis()}.m4a")
        val media = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = media
        output = file
        recording = true
        return file
    }

    /** Stops and returns the file, or null if nothing usable was captured. */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        val file = output
        output = null
        val media = recorder
        recorder = null
        try {
            media?.stop()
        } catch (_: Throwable) {
            runCatching { media?.reset() }
            file?.delete()
            media?.release()
            return null
        }
        media?.release()
        if (file == null || !file.isFile || file.length() < 800) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancel() {
        recording = false
        val file = output
        output = null
        val media = recorder
        recorder = null
        runCatching { media?.stop() }
        runCatching { media?.reset() }
        media?.release()
        file?.delete()
    }

    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }
}
