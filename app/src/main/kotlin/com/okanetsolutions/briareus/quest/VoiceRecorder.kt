package com.okanetsolutions.briareus.quest

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records a voice note with the headset's microphone as AAC in MP4, which the server transcribes (`POST /transcribe`,
 * `audio/mp4`). Typing in a headset is slow; this is the quick way to write.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val recording: Boolean get() = recorder != null

    fun start() {
        stopQuietly()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(context)
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(96_000)
        r.setMaxDuration(10 * 60 * 1000)
        r.setOutputFile(out)
        r.prepare()
        r.start()
        recorder = r
        file = out
    }

    /** The recording's bytes, or null when it was too short to keep. The file is deleted either way. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val bytes = if (ok) f?.readBytes() else null
        f?.delete()
        return bytes?.takeIf { it.size > 1024 }
    }

    fun stopQuietly() {
        stop()
    }

    companion object {
        const val CONTENT_TYPE = "audio/mp4"
    }
}
