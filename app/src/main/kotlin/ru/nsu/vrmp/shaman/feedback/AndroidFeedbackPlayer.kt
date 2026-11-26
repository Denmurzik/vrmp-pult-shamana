package ru.nsu.vrmp.shaman.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import ru.nsu.vrmp.shaman.core.feedback.CommandFeedbackKind
import ru.nsu.vrmp.shaman.core.feedback.CuePattern
import ru.nsu.vrmp.shaman.core.feedback.FeedbackCue
import ru.nsu.vrmp.shaman.core.feedback.FeedbackPlanner
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

interface FeedbackPlayer {
    fun play(cue: FeedbackCue)
    fun release()
}

object NoOpFeedbackPlayer : FeedbackPlayer {
    override fun play(cue: FeedbackCue) = Unit
    override fun release() = Unit
}

/**
 * Plays haptic pulses via [Vibrator] and synthesized sine cues via [AudioTrack].
 */
class AndroidFeedbackPlayer(context: Context) : FeedbackPlayer {

    private val vibrator: Vibrator? = resolveVibrator(context)
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var activeTrack: AudioTrack? = null

    override fun play(cue: FeedbackCue) {
        when (cue) {
            is FeedbackCue.ProximityPulse -> {
                vibrateOneShot(durationMs = 35L, amplitude = cue.amplitude)
                playToneSequence(doubleArrayOf(cue.frequencyHz), durationPerToneMs = 45)
            }

            is FeedbackCue.CommandFeedback -> {
                val pattern = FeedbackPlanner.patternFor(cue.kind)
                vibratePattern(pattern)
                playToneSequence(pattern.tonesHz, pattern.toneDurationMs)
            }
        }
    }

    override fun release() {
        audioExecutor.execute {
            activeTrack?.runCatching {
                stop()
                release()
            }
            activeTrack = null
        }
        audioExecutor.shutdown()
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val amp = if (vib.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        vib.vibrate(VibrationEffect.createOneShot(durationMs, amp))
    }

    private fun vibratePattern(pattern: CuePattern) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (vib.hasAmplitudeControl()) {
            vib.vibrate(VibrationEffect.createWaveform(pattern.timingsMs, pattern.amplitudes, -1))
        } else {
            vib.vibrate(VibrationEffect.createWaveform(pattern.timingsMs, -1))
        }
    }

    private fun playToneSequence(frequenciesHz: DoubleArray, durationPerToneMs: Int) {
        if (frequenciesHz.isEmpty()) return
        audioExecutor.execute {
            val pcm = synthesizeSequence(frequenciesHz, durationPerToneMs)
            activeTrack?.runCatching {
                stop()
                release()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            activeTrack = track
        }
    }

    private fun synthesizeSequence(frequenciesHz: DoubleArray, durationPerToneMs: Int): ShortArray {
        val samplesPerTone = (SAMPLE_RATE * durationPerToneMs) / 1000
        val gapSamples = (SAMPLE_RATE * 15) / 1000
        val fadeSamples = (SAMPLE_RATE * 8) / 1000
        val totalSamples = frequenciesHz.size * samplesPerTone + (frequenciesHz.size - 1) * gapSamples
        val buffer = ShortArray(totalSamples)

        var offset = 0
        frequenciesHz.forEachIndexed { index, freq ->
            for (i in 0 until samplesPerTone) {
                val env = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > samplesPerTone - fadeSamples -> (samplesPerTone - i).toDouble() / fadeSamples
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val sample = sin(2.0 * PI * freq * i / SAMPLE_RATE) * env * 0.45
                buffer[offset + i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            offset += samplesPerTone
            if (index < frequenciesHz.lastIndex) {
                offset += gapSamples
            }
        }
        return buffer
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
