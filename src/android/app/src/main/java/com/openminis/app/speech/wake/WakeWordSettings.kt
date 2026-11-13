package com.openminis.app.speech.wake

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent settings for background wake-word listening.
 *
 * Kept in its own preferences file rather than in [BackgroundSettingsRepository]:
 * the wake pipeline owns a microphone foreground service and its own overlay
 * position, so a single file keeps the service, the overlay and the settings
 * screen reading the same values.
 */
object WakeWordSettings {

    const val PREFS_NAME = "voice_wake"
    const val KEY_ENABLED = "enabled"
    const val KEY_WAKE_WORD = "wakeWord"
    const val KEY_OVERLAY_ENABLED = "showOverlay"
    const val KEY_TONE_ENABLED = "wakeTone"
    const val KEY_VOICEPRINT_ENABLED = "voiceprint"
    const val KEY_VOICE_SESSION_ID = "voiceSessionId"
    const val KEY_VOICEPRINT_THRESHOLD = "voiceprintThreshold"

    private const val KEY_OVERLAY_X = "overlayX"
    private const val KEY_OVERLAY_Y = "overlayY"
    private const val KEY_OVERLAY_LAND_X = "overlayLandscapeX"
    private const val KEY_OVERLAY_LAND_Y = "overlayLandscapeY"

    /**
     * Four syllables, all present in the bundled KWS model's token table.
     *
     * "Minis" itself cannot be used: the model is a Chinese pinyin KWS model,
     * and ICU's Han-Latin transliterator leaves Latin text untouched, which
     * produces tokens the model has never seen. A Chinese name keeps the
     * dynamic pinyin path (and the built-in fallback) valid.
     */
    const val DEFAULT_WAKE_WORD = "你好米尼斯"

    /** Cosine threshold for the voiceprint gate — see [voiceprintThreshold]. */
    const val DEFAULT_VOICEPRINT_THRESHOLD = 0.30f
    const val MIN_VOICEPRINT_THRESHOLD = 0.05f
    const val MAX_VOICEPRINT_THRESHOLD = 0.90f

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _wakeWord = MutableStateFlow(DEFAULT_WAKE_WORD)
    val wakeWord: StateFlow<String> = _wakeWord.asStateFlow()

    private val _showOverlay = MutableStateFlow(true)
    val showOverlay: StateFlow<Boolean> = _showOverlay.asStateFlow()

    private val _toneEnabled = MutableStateFlow(true)
    val toneEnabled: StateFlow<Boolean> = _toneEnabled.asStateFlow()

    /**
     * Whether the voiceprint gates the wake word.
     *
     * On by default: the wake word is public, and the gate is what keeps a
     * stranger from starting a conversation. With no enrolled template the
     * gate lets everything through (see [WakeVoiceprintGate]) — the first
     * utterance enrolls the owner.
     */
    private val _voiceprintEnabled = MutableStateFlow(true)
    val voiceprintEnabled: StateFlow<Boolean> = _voiceprintEnabled.asStateFlow()

    /** The session the background conversation runs in. */
    private val _voiceSessionId = MutableStateFlow<String?>(null)
    val voiceSessionId: StateFlow<String?> = _voiceSessionId.asStateFlow()

    /**
     * Cosine similarity a voiceprint must reach to be accepted.
     *
     * User-tunable because the right value depends on the room, the
     * distance to the phone and the voice: pilot measured the owner at
     * 0.39–0.52 and the assistant's own TTS echo at 0.02–0.11 on a real
     * device, so 0.30 is a sane default — but a noisy desk or a quiet
     * speaker needs a different one, and the user is the only one who
     * knows.
     */
    private val _voiceprintThreshold = MutableStateFlow(DEFAULT_VOICEPRINT_THRESHOLD)
    val voiceprintThreshold: StateFlow<Float> = _voiceprintThreshold.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _enabled.value = p.getBoolean(KEY_ENABLED, false)
            _wakeWord.value = p.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD)
                ?.takeIf { parseWords(it).isNotEmpty() }
                ?: DEFAULT_WAKE_WORD
            _showOverlay.value = p.getBoolean(KEY_OVERLAY_ENABLED, true)
            _toneEnabled.value = p.getBoolean(KEY_TONE_ENABLED, true)
            _voiceprintEnabled.value = p.getBoolean(KEY_VOICEPRINT_ENABLED, true)
            _voiceSessionId.value = p.getString(KEY_VOICE_SESSION_ID, null)
            _voiceprintThreshold.value = p
                .getFloat(KEY_VOICEPRINT_THRESHOLD, DEFAULT_VOICEPRINT_THRESHOLD)
                .coerceIn(MIN_VOICEPRINT_THRESHOLD, MAX_VOICEPRINT_THRESHOLD)
            // Config CLI writes the same keys directly; without this listener
            // the flows would keep their construction-time values.
            p.registerOnSharedPreferenceChangeListener { sp, key ->
                when (key) {
                    KEY_ENABLED -> _enabled.value = sp.getBoolean(key, false)
                    KEY_WAKE_WORD -> _wakeWord.value =
                        sp.getString(key, DEFAULT_WAKE_WORD)
                            ?.takeIf { parseWords(it).isNotEmpty() }
                            ?: DEFAULT_WAKE_WORD
                    KEY_OVERLAY_ENABLED -> _showOverlay.value = sp.getBoolean(key, true)
                    KEY_TONE_ENABLED -> _toneEnabled.value = sp.getBoolean(key, true)
                    KEY_VOICEPRINT_ENABLED -> _voiceprintEnabled.value = sp.getBoolean(key, true)
                    KEY_VOICE_SESSION_ID -> _voiceSessionId.value = sp.getString(key, null)
                    KEY_VOICEPRINT_THRESHOLD ->
                        _voiceprintThreshold.value = sp
                            .getFloat(key, DEFAULT_VOICEPRINT_THRESHOLD)
                            .coerceIn(MIN_VOICEPRINT_THRESHOLD, MAX_VOICEPRINT_THRESHOLD)
                }
            }
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _enabled.value = value
    }

    fun setWakeWord(context: Context, raw: String): Boolean {
        init(context)
        val words = parseWords(raw)
        if (words.isEmpty()) return false
        val normalized = words.joinToString(",")
        prefs?.edit()?.putString(KEY_WAKE_WORD, normalized)?.apply()
        _wakeWord.value = normalized
        return true
    }

    fun setShowOverlay(context: Context, value: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_OVERLAY_ENABLED, value)?.apply()
        _showOverlay.value = value
    }

    fun setToneEnabled(context: Context, value: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_TONE_ENABLED, value)?.apply()
        _toneEnabled.value = value
    }

    fun setVoiceprintEnabled(context: Context, value: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_VOICEPRINT_ENABLED, value)?.apply()
        _voiceprintEnabled.value = value
    }

    fun setVoiceSessionId(context: Context, sessionId: String?) {
        init(context)
        prefs?.edit()?.putString(KEY_VOICE_SESSION_ID, sessionId)?.apply()
        _voiceSessionId.value = sessionId
    }

    fun setVoiceprintThreshold(context: Context, value: Float) {
        init(context)
        val clamped = value.coerceIn(MIN_VOICEPRINT_THRESHOLD, MAX_VOICEPRINT_THRESHOLD)
        prefs?.edit()?.putFloat(KEY_VOICEPRINT_THRESHOLD, clamped)?.apply()
        _voiceprintThreshold.value = clamped
    }

    /**
     * Forget the enrolled owner voiceprint.
     *
     * Switching the gate off has to mean "the template is gone too":
     * a stale template would still be compared against the next time the
     * gate is switched back on, and the owner would be locked out by a
     * recording from a different day.
     */
    fun clearVoiceprintTemplate(context: Context) {
        init(context)
        WakeVoiceprintGate.clearTemplate(context)
    }

    /** Configured wake words, preserving order and dropping duplicates. */
    fun wakeWordList(): List<String> = parseWords(_wakeWord.value)

    fun parseWords(raw: String): List<String> =
        raw.split(',', '，', '、')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.length in 1..12 }
            .distinct()
            .take(4)
            .toList()

    fun getOverlayPosition(landscape: Boolean): Pair<Int, Int>? {
        val p = prefs ?: return null
        val (kx, ky) = overlayKeys(landscape)
        if (!p.contains(kx) || !p.contains(ky)) return null
        return p.getInt(kx, 0) to p.getInt(ky, 0)
    }

    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean) {
        val p = prefs ?: return
        val (kx, ky) = overlayKeys(landscape)
        p.edit().putInt(kx, x).putInt(ky, y).apply()
    }

    private fun overlayKeys(landscape: Boolean): Pair<String, String> =
        if (landscape) KEY_OVERLAY_LAND_X to KEY_OVERLAY_LAND_Y else KEY_OVERLAY_X to KEY_OVERLAY_Y
}
