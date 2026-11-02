package dev.hermeskotlin.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Takes the phone's audio focus for as long as the microphone is open, so whatever is playing — music, a video —
 * pauses, and starts again by itself as soon as focus is [drop]ped.
 */
internal class MicAudioFocus(context: Context) {

    private val manager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    fun take() = manager?.requestAudioFocus(request)

    fun drop() = manager?.abandonAudioFocusRequest(request)
}
