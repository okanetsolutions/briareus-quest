package com.okanetsolutions.briareus.quest

import android.content.Context
import androidx.core.content.edit
import com.okanetsolutions.briareus.core.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the voice mode connects with: the OpenAI API key, sealed in the [Vault] with the token (so forgetting the
 * connection erases it too), and the voice and silence limit, kept in plain settings.
 */
class VoiceSettings(context: Context, private val store: Store) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(key() != null)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()
    private val _voice = MutableStateFlow(prefs.getString("voice", null)?.takeIf { it in Voice.VOICES } ?: Voice.DEFAULT_VOICE)
    val voice: StateFlow<String> = _voice.asStateFlow()
    /** Minutes of silence after which a conversation ends by itself, as GPT-Realtime bills the audio it hears; 0 is never. */
    private val _idleMinutes = MutableStateFlow(prefs.getInt("idle_minutes", 3))
    val idleMinutes: StateFlow<Int> = _idleMinutes.asStateFlow()

    init {
        // Forgetting the connection destroys the vault's key, and the OpenAI key with it.
        store.scope.launch { store.connection.collect { _hasKey.value = key() != null } }
    }

    fun key(): String? = store.vault.getString(KEY)

    /** Reads again whether a key is saved, as the panel opens. */
    fun refresh() {
        _hasKey.value = key() != null
    }

    fun save(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        store.vault.putString(KEY, k)
        _hasKey.value = true
    }

    fun removeKey() {
        store.vault.putString(KEY, null)
        _hasKey.value = false
    }

    fun setVoice(voice: String) {
        if (voice !in Voice.VOICES) return
        _voice.value = voice
        prefs.edit { putString("voice", voice) }
    }

    fun setIdleMinutes(minutes: Int) {
        val m = minutes.coerceIn(0, 30)
        _idleMinutes.value = m
        prefs.edit { putInt("idle_minutes", m) }
    }

    private companion object {
        const val KEY = "openai_key"
    }
}
