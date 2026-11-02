package dev.hermeskotlin.core.voice

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The capture line is asked about without a microphone on a build machine, as on any computer that has none: the
 * answer is a plain no, and dictation says so rather than starting a recording that never ends.
 */
class DesktopVoiceRecorderTest {

    @Test
    fun withoutAMicrophoneDictationSaysSoInsteadOfRecording() = runBlocking {
        val recorder = DesktopVoiceRecorder()
        // Recorded here for the record: a machine with a microphone would open a real line, and this is its no.
        if (recorder.available()) return@runBlocking
        val refused = runCatching { recorder.record(VoiceActivity(maxMs = 100)) }
        assertTrue(refused.isFailure, "no microphone is an error, not a silent empty recording")
        assertTrue(
            refused.exceptionOrNull()?.message?.contains("microphone") == true,
            "the message reads as one about the microphone: ${refused.exceptionOrNull()?.message}",
        )
    }
}
