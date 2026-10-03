package com.okanetsolutions.briareus.quest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.okanetsolutions.briareus.core.BriareusJson
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.core.str
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A call to GPT-Realtime over a WebSocket: the headset's microphone goes out and the voice comes back as 16-bit PCM at
 * 24 kHz, with JSON events beside them. The microphone and the voice run as a call (echo cancellation and noise
 * suppression on), so the model does not hear itself through the headset's speakers. The OpenAI key is sent only to
 * OpenAI, to open the call. One call per conversation: [hangUp] ends it for good.
 */
class RealtimeCall(context: Context) {
    class Failure(message: String) : Exception(message)

    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val http = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var muted = false
    @Volatile private var closed = false
    private var player: Player? = null
    private var previousMode = AudioManager.MODE_NORMAL

    /**
     * Opens the call with [session] as its first `session.update`, and answers the model's events until it ends. The
     * voice's audio is played here, not passed on; each of its chunks is passed on as a bare `response.output_audio.delta`.
     */
    fun open(key: String, session: JsonObject): Flow<JsonObject> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw Failure("Allow Briareus to use the microphone.")
        }
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val player = Player().also { player = it }
        val request = Request.Builder().url(Voice.ENDPOINT).header("Authorization", "Bearer $key").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(args("type" to "session.update", "session" to session).toString())
                try {
                    microphone()
                } catch (e: Exception) {
                    channel.close(e as? Failure ?: Failure("The microphone could not start."))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { BriareusJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                when (event.str("type")) {
                    "response.output_audio.delta" -> {
                        val pcm = runCatching { Base64.getDecoder().decode(event.str("delta").orEmpty()) }.getOrNull() ?: return
                        player.play(event.str("item_id"), pcm)
                        channel.trySend(AUDIO)
                        return
                    }
                    // The user talks over the voice: it stops at once, and the model is told how much of it was heard.
                    "input_audio_buffer.speech_started" -> player.interrupt()?.let { webSocket.send(it.toString()) }
                }
                channel.trySend(event)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = when (response?.code) {
                    401, 403 -> Failure("OpenAI refused the API key. Check it in the voice settings.")
                    null -> Failure(if (closed) "The call ended." else "The connection with GPT-Realtime was lost.")
                    else -> Failure("GPT-Realtime did not start the conversation: HTTP ${response.code}.")
                }
                channel.close(failure)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close(if (code == 1000 || closed) null else Failure(reason.ifBlank { "GPT-Realtime ended the conversation." }))
            }
        })
        awaitClose { hangUp() }
    }.buffer(Channel.UNLIMITED)

    fun send(event: JsonObject) {
        socket?.send(event.toString())
    }

    /** While muted, nothing the microphone hears is sent. */
    fun mute(on: Boolean) {
        muted = on
    }

    fun hangUp() {
        if (closed) return
        closed = true
        socket?.close(1000, null)
        socket = null
        player?.release()
        player = null
        audio.mode = previousMode
    }

    /** Sends what the microphone hears in 40 ms pieces until the call ends. */
    private fun microphone() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw Failure("Allow Briareus to use the microphone.")
        }
        val rate = Voice.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw Failure("The microphone could not start.")
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        record.startRecording()
        thread(name = "voice-microphone", isDaemon = true) {
            val buffer = ByteArray(rate / 25 * 2)
            val encoder = Base64.getEncoder()
            try {
                while (!closed) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0 || muted) continue
                    val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                    socket?.send("""{"type":"input_audio_buffer.append","audio":"${encoder.encodeToString(chunk)}"}""")
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                echo?.release()
                noise?.release()
            }
        }
    }

    /** Plays the voice as it arrives, and stops it at once when the user talks over it. */
    private inner class Player {
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Voice.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(Voice.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), Voice.SAMPLE_RATE / 2))
            .build()
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val lock = Any()
        /** The item playing, the frame it started on, and every frame queued since the last flush. */
        private var item: String? = null
        private var itemStart = 0L
        private var queued = 0L
        /** The item the user cut off: what is still on its way of it is not played. */
        private var dropped: String? = null
        @Volatile private var released = false

        init {
            track.play()
            thread(name = "voice-player", isDaemon = true) {
                while (!released) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    track.write(chunk, 0, chunk.size)
                }
                runCatching { track.stop() }
                track.release()
            }
        }

        fun play(itemId: String?, pcm: ByteArray) = synchronized(lock) {
            if (itemId != null && itemId == dropped) return@synchronized
            if (itemId != item) { item = itemId; itemStart = queued }
            queued += pcm.size / 2
            queue.add(pcm)
        }

        /** Stops the voice; the `conversation.item.truncate` to send when some of it went unheard, else null. */
        fun interrupt(): JsonObject? = synchronized(lock) {
            val id = item ?: return@synchronized null
            // The head counts frames played since the last flush, as `queued` does.
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val unheard = queued > head
            val played = (head - itemStart).coerceIn(0L, queued - itemStart)
            queue.clear()
            track.pause()
            track.flush()
            track.play()
            dropped = id; item = null; itemStart = 0; queued = 0
            if (!unheard) null
            else args("type" to "conversation.item.truncate", "item_id" to id, "content_index" to 0, "audio_end_ms" to played * 1000 / Voice.SAMPLE_RATE)
        }

        fun release() {
            released = true
            queue.clear()
            runCatching { track.pause(); track.flush() }
        }
    }

    companion object {
        /** What is passed on for each chunk of the voice, without its audio. */
        val AUDIO = args("type" to "response.output_audio.delta")
    }
}
