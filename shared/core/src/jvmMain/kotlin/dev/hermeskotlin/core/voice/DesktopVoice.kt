package dev.hermeskotlin.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM from the computer's microphone with the JDK's capture line and hands it over as WAV, the
 * shape every speech-to-text provider takes — the same one the phone records, so the gateway transcription and the
 * end-of-speech timings are the ones the chat already uses. The level is the phone's: RMS of the waveform, scaled so
 * 1.0 is loud speech.
 *
 * A computer has no speech recognizer to dictate with locally, so the gateway always writes it down.
 */
class DesktopVoiceRecorder : VoiceRecorder {

    @Volatile private var finishRequested = false

    /** Whether this computer has a microphone the JDK can open. */
    override fun available(): Boolean = runCatching { AudioSystem.isLineSupported(LINE_INFO) }.getOrDefault(false)

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
        withContext(Dispatchers.IO) {
            finishRequested = false
            val line = runCatching { AudioSystem.getLine(LINE_INFO) as TargetDataLine }
                .getOrElse { error("The microphone isn't available.") }
            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(FRAME_SAMPLES * 2)
            val endOfSpeech = EndOfSpeech(activity)
            var elapsed = 0L
            // Frames counted per recording, so the level's throttle starts over with the next one.
            var frames = 0
            try {
                line.open(FORMAT, buffer.size * 4)
                line.start()
                while (!finishRequested) {
                    ensureActive()
                    val read = line.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    pcm.write(buffer, 0, read)
                    val samples = read / 2
                    elapsed += samples * 1000L / SAMPLE_RATE
                    val level = level(buffer, samples)
                    // Every third frame is 17 Hz: as smooth to the eye as 50, and a third of the redraws.
                    if (frames++ % LEVEL_EVERY == 0) onLevel(level)
                    val heardBefore = endOfSpeech.heardSpeech
                    val done = endOfSpeech.onFrame(level, elapsed)
                    if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                    if (done) break
                }
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
                onLevel(0f)
            }
            val heard = endOfSpeech.heardSpeech || finishRequested && pcm.size() > SAMPLE_RATE
            Recording(wav(pcm.toByteArray(), SAMPLE_RATE), "audio/wav", heard)
        }

    override fun finish() {
        finishRequested = true
    }

    /** The phone measures 8-bit samples (±128) against 42; 16-bit samples are 256 times larger. */
    private fun level(buffer: ByteArray, samples: Int): Float {
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return min(1.0, sqrt(sum / samples) / 256.0 / 42.0).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms frames: fine-grained enough for the silence timer. */
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
        const val LEVEL_EVERY = 3

        val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val LINE_INFO = DataLine.Info(TargetDataLine::class.java, FORMAT)
    }
}

/** No speaker on the desktop yet: replies aren't read aloud. */
object NoSpeechPlayer : SpeechPlayer {
    override suspend fun play(audio: SpokenAudio) = Unit
}
