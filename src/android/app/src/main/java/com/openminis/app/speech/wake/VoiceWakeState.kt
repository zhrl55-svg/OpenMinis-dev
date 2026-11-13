package com.openminis.app.speech.wake

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the wake pipeline is doing right now. */
enum class VoiceWakePhase {
    /** Feature disabled, or service stopped. */
    OFF,

    /** Microphone open, KWS waiting for the wake word. */
    WAITING,

    /** Wake word matched; the pipeline is waking up. */
    WAKING,

    /**
     * Wake word matched and the voiceprint passed: the microphone is now
     * recording the user's request and nothing else is listening.
     */
    LISTENING,

    /** The agent's reply is being spoken. */
    REPLYING,

    /** Intentionally not listening (TTS, another recognizer, in-app capture). */
    PAUSED,

    /** The pipeline could not start or lost the microphone. */
    ERROR,
}

data class VoiceWakeSnapshot(
    val phase: VoiceWakePhase = VoiceWakePhase.OFF,
    val detail: String = "",
)

/**
 * Process-wide state for the wake service, the floating status ball and the
 * diagnostics file. Updates are deduplicated so a 400 ms gate loop does not
 * rewrite the same line forever.
 */
object VoiceWakeState {

    private const val TAG = "VoiceWake"
    private const val TRACE_FILE = "voice_wake.log"

    private val _state = MutableStateFlow(VoiceWakeSnapshot())
    val state: StateFlow<VoiceWakeSnapshot> = _state.asStateFlow()

    fun update(context: Context, phase: VoiceWakePhase, detail: String = "") {
        val next = VoiceWakeSnapshot(phase, detail)
        if (_state.value == next) return
        _state.value = next
        AppLogger.info(TAG, "${phase.name} $detail")
        trace(context, "${phase.name} $detail")
    }

    /** Diagnostics at `getExternalFilesDir(null)/voice_wake.log` (adb-readable). */
    fun trace(context: Context, line: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(dir, TRACE_FILE).appendText("[$stamp] $line\n")
        }
    }
}
