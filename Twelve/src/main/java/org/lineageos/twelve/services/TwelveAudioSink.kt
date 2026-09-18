/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * An [AudioSink] implementation that wraps a [DefaultAudioSink] and exposes the routed
 * [AudioDeviceInfo] used by the [DefaultAudioSink].
 */
@androidx.media3.common.util.UnstableApi
class TwelveAudioSink(
    private val defaultAudioSink: DefaultAudioSink,
    private val onAudioDeviceInfoChanged: (AudioDeviceInfo?) -> Unit,
    private val onAudioTrackConfigChanged: (AudioSink.AudioTrackConfig?) -> Unit,
) : AudioSink by defaultAudioSink {
    private val audioOutputField = DefaultAudioSink::class.java.getDeclaredField(
        "audioOutput"
    ).apply {
        isAccessible = true
    }
    private val audioTrackField = AudioTrackAudioOutput::class.java.getDeclaredField(
        "audioTrack"
    ).apply {
        isAccessible = true
    }

    private val audioTrack
        get() = audioOutputField.get(defaultAudioSink)?.let {
            audioTrackField.get(it) as AudioTrack?
        }

    private val handler = Handler(Looper.getMainLooper())

    private val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
        onAudioDeviceInfoChanged(routing.routedDevice)
    }

    private var currentAudioTrack: AudioTrack? = null

    private var externalListener: AudioSink.Listener? = null

    private val proxyListener = object : AudioSink.Listener {
        override fun onPositionDiscontinuity() {
            externalListener?.onPositionDiscontinuity()
        }

        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            externalListener?.onPositionAdvancing(playoutStartSystemTimeMs)
        }

        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            externalListener?.onUnderrun(
                bufferSize,
                bufferSizeMs,
                elapsedSinceLastFeedMs,
            )
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            externalListener?.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        }

        override fun onOffloadBufferEmptying() {
            externalListener?.onOffloadBufferEmptying()
        }

        override fun onOffloadBufferFull() {
            externalListener?.onOffloadBufferFull()
        }

        override fun onAudioSinkError(audioSinkError: Exception) {
            externalListener?.onAudioSinkError(audioSinkError)
        }

        override fun onAudioCapabilitiesChanged() {
            externalListener?.onAudioCapabilitiesChanged()
        }

        override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
            currentAudioTrack?.removeOnRoutingChangedListener(routingListener)

            val track = audioTrack
            currentAudioTrack = track

            onAudioTrackConfigChanged(audioTrackConfig)
            track?.addOnRoutingChangedListener(routingListener, handler)
            onAudioDeviceInfoChanged(track?.routedDevice)

            externalListener?.onAudioTrackInitialized(audioTrackConfig)
        }

        override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
            if (currentAudioTrack == null) {
                onAudioTrackConfigChanged(null)
                onAudioDeviceInfoChanged(null)
            }

            externalListener?.onAudioTrackReleased(audioTrackConfig)
        }

        override fun onSilenceSkipped() {
            externalListener?.onSilenceSkipped()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            externalListener?.onAudioSessionIdChanged(audioSessionId)
        }
    }

    init {
        defaultAudioSink.setListener(proxyListener)
    }

    override fun setListener(listener: AudioSink.Listener) {
        externalListener = listener
    }

    override fun setPlayerId(playerId: PlayerId?) {
        defaultAudioSink.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        defaultAudioSink.setClock(clock)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return defaultAudioSink.getFormatOffloadSupport(format)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        defaultAudioSink.configure(audioSinkConfig)
    }

    override fun getAudioCapabilities(): AudioCapabilities? {
        return defaultAudioSink.getAudioCapabilities()
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        defaultAudioSink.setPreferredDevice(audioDeviceInfo)
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        defaultAudioSink.setVirtualDeviceId(virtualDeviceId)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        defaultAudioSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun setOffloadMode(offloadMode: Int) {
        defaultAudioSink.setOffloadMode(offloadMode)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        defaultAudioSink.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        defaultAudioSink.setAudioOutputProvider(audioOutputProvider)
    }

    override fun flush() {
        defaultAudioSink.flush()
    }

    override fun release() {
        audioTrack?.removeOnRoutingChangedListener(routingListener)
        defaultAudioSink.release()
    }
}
