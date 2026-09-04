package com.sidetrack.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Receives PCM audio data from the native layer via JNI callback and feeds it
 * to Android's AudioTrack for playback.
 *
 * The native side calls onAudioData(byte[]) on a Rust thread attached to the JVM.
 * We write the bytes directly into an AudioTrack configured for 44100 Hz stereo S16LE.
 */
class AudioCallback {

    private var audioTrack: AudioTrack? = null

    /** Called by native code to deliver PCM audio samples. */
    @Suppress("unused") // Called from JNI
    fun onAudioData(data: ByteArray) {
        try {
            val track = audioTrack ?: createAudioTrack().also { audioTrack = it }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(data, 0, data.size)
        } catch (e: IllegalStateException) {
            // AudioTrack was invalidated/released — recreate it
            audioTrack = null
            try {
                val track = createAudioTrack().also { audioTrack = it }
                track.play()
                track.write(data, 0, data.size)
            } catch (_: Exception) {}
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun release() {
        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
    }
}
