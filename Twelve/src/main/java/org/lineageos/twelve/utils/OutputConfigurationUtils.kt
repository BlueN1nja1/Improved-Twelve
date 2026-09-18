/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.lineageos.twelve.models.OutputConfiguration
import org.lineageos.twelve.models.OutputConfiguration.Transcoding.OutputMode

/**
 * Audio quality classifier.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object OutputConfigurationUtils {
    private val LOG_TAG = OutputConfigurationUtils::class.simpleName!!

    private val mimeTypeToCompression = mapOf(
        MimeTypes.AUDIO_MP4 to null, // container
        MimeTypes.AUDIO_AAC to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MATROSKA to null, // container
        MimeTypes.AUDIO_WEBM to null, // container
        MimeTypes.AUDIO_MPEG to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MPEG_L1 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MPEG_L2 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MPEGH_MHA1 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MPEGH_MHM1 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_RAW to OutputConfiguration.Compression.UNCOMPRESSED,
        MimeTypes.AUDIO_ALAW to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_MLAW to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_AC3 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_E_AC3 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_E_AC3_JOC to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_AC4 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_TRUEHD to OutputConfiguration.Compression.LOSSLESS,
        MimeTypes.AUDIO_DTS to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_DTS_HD to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_DTS_EXPRESS to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_DTS_UHD_P2 to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_VORBIS to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_OPUS to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_AMR to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_AMR_NB to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_AMR_WB to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_FLAC to OutputConfiguration.Compression.LOSSLESS,
        MimeTypes.AUDIO_ALAC to OutputConfiguration.Compression.LOSSLESS,
        MimeTypes.AUDIO_MSGSM to OutputConfiguration.Compression.LOSSY,
        MimeTypes.AUDIO_OGG to null, // container
        MimeTypes.AUDIO_WAV to null, // container
        MimeTypes.AUDIO_MIDI to null, // sequence
        MimeTypes.AUDIO_IAMF to null, // container
    )

    private val pcmEncodingBitDepths = mapOf(
        OutputConfiguration.Encoding.PCM_8_BIT to 8,
        OutputConfiguration.Encoding.PCM_16_BIT to 16,
        OutputConfiguration.Encoding.PCM_24_BIT to 24,
        OutputConfiguration.Encoding.PCM_24_BIT_PACKED to 24,
        OutputConfiguration.Encoding.PCM_32_BIT to 32,
        OutputConfiguration.Encoding.PCM_FLOAT to 32,
    )

    @JvmName("classifyNullable")
    fun classify(
        source: OutputConfiguration.Source?,
        transcoding: OutputConfiguration.Transcoding?,
        device: OutputConfiguration.Device?,
    ): OutputConfiguration? {
        return classify(
            source = source ?: return null,
            transcoding = transcoding ?: return null,
            device = device ?: return null,
        )
    }

    fun Format.toModel(): OutputConfiguration.Source {
        val streamMimeType = getStreamMimeType()
        val encoding = guessEncoding()

        return OutputConfiguration.Source(
            sampleRateHz = sampleRate.takeIf { it != Format.NO_VALUE },
            channelCount = channelCount.takeIf { it != Format.NO_VALUE },
            mimeType = streamMimeType,
            encoding = encoding,
            compression = mimeTypeToCompression[streamMimeType] ?: encoding?.compression,
            peakBitrateBps = peakBitrate.takeIf { it != Format.NO_VALUE },
            averageBitrateBps = averageBitrate.takeIf { it != Format.NO_VALUE },
        )
    }

    fun buildOutputTranscoding(
        audioTrackConfig: AudioSink.AudioTrackConfig?,
        format: Format?,
    ): OutputConfiguration.Transcoding? {
        audioTrackConfig ?: return null

        return OutputConfiguration.Transcoding(
            outputMode = outputModeFromAudioTrackConfig(audioTrackConfig),
            encoding = encodingFromMedia3Encoding(audioTrackConfig.encoding),
            sampleRateHz = format?.sampleRate?.takeIf { it != AudioFormat.SAMPLE_RATE_UNSPECIFIED },
            channelCount = format?.channelCount?.takeIf { it != 0 },
            bitrateBps = format?.bitrate?.takeIf { it != Format.NO_VALUE },
        )
    }

    fun AudioDeviceInfo.toModel() = OutputConfiguration.Device(
        name = productName.toString(),
        type = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputConfiguration.Device.Type.BUILTIN

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputConfiguration.Device.Type.HEADPHONES

            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_DOCK_ANALOG -> OutputConfiguration.Device.Type.EXTERNAL_SPEAKERS

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputConfiguration.Device.Type.BLUETOOTH

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> OutputConfiguration.Device.Type.HDMI

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputConfiguration.Device.Type.USB

            AudioDeviceInfo.TYPE_IP,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> OutputConfiguration.Device.Type.REMOTE

            AudioDeviceInfo.TYPE_HEARING_AID -> OutputConfiguration.Device.Type.HEARING_AID

            else -> run {
                Log.e(LOG_TAG, "Unknown device type: $type")
                null
            }
        },
        sampleRatesHz = sampleRates.toSet(),
        channelCounts = channelCounts.toSet(),
        encodings = encodings.toList().mapNotNull {
            encodingFromAudioFormatEncoding(it)
        }.toSet(),
    )

    private fun classify(
        source: OutputConfiguration.Source,
        transcoding: OutputConfiguration.Transcoding,
        device: OutputConfiguration.Device,
    ): OutputConfiguration {
        val isDownsampling = run {
            val sampleRatesHz = listOf(
                source.sampleRateHz,
                transcoding.sampleRateHz,
                device.sampleRatesHz.maxOrNull(),
            )

            var previousSampleRate: Int? = null
            sampleRatesHz.forEach {
                previousSampleRate = previousSampleRate ?: it

                it?.let { currentSampleRate ->
                    previousSampleRate?.let { previousSampleRate ->
                        if (currentSampleRate < previousSampleRate) {
                            return@run true
                        }
                    }
                }
            }

            false
        }

        val isPcmFloatModeDisabled = run {
            val sourceEncoding = source.encoding ?: return@run false
            val transcodingEncoding = transcoding.encoding ?: return@run false

            val sourcePcmBitDepth = pcmEncodingBitDepths[sourceEncoding] ?: return@run false
            val transcodingPcmBitDepth =
                pcmEncodingBitDepths[transcodingEncoding] ?: return@run false

            sourcePcmBitDepth > transcodingPcmBitDepth
        }

        val isDownmixing = run {
            val channelCounts = listOf(
                source.channelCount,
                transcoding.channelCount,
                device.channelCounts.maxOrNull(),
            )

            var previousChannelCount: Int? = null
            channelCounts.forEach {
                previousChannelCount = previousChannelCount ?: it

                it?.let { currentChannelCount ->
                    previousChannelCount?.let { previousChannelCount ->
                        if (currentChannelCount < previousChannelCount) {
                            return@run true
                        }
                    }
                }
            }

            false
        }

        val hasPostProcessingCompression = run {
            if (source.compression == OutputConfiguration.Compression.LOSSY) {
                return@run false
            }

            if (transcoding.encoding?.compression == OutputConfiguration.Compression.LOSSY) {
                return@run true
            }

            false
        }

        val potentialIssues = buildSet {
            if (isDownsampling) {
                add(OutputConfiguration.Verdict.Issue.DOWNSAMPLING)
            }

            if (isPcmFloatModeDisabled) {
                add(OutputConfiguration.Verdict.Issue.PCM_FLOAT_MODE_DISABLED)
            }

            if (isDownmixing) {
                add(OutputConfiguration.Verdict.Issue.DOWNMIXING)
            }

            if (hasPostProcessingCompression) {
                add(OutputConfiguration.Verdict.Issue.POST_PROCESSING_LOSSY_COMPRESSION)
            }
        }

        val isHiRes = run {
            if (potentialIssues.isNotEmpty()) {
                return@run false
            }

            val sampleRate = source.sampleRateHz ?: return@run false
            val encoding = source.encoding ?: return@run false
            val compression = source.compression ?: return@run false

            if (compression == OutputConfiguration.Compression.LOSSY) {
                return@run false
            }

            pcmEncodingBitDepths[encoding]?.let {
                if (it < 24) {
                    return@run false
                }
            }

            val minHiResSampleRateHz = when (encoding) {
                OutputConfiguration.Encoding.DSD -> 2822400 // 2.8224 MHz
                else -> 96000
            }

            if (sampleRate < minHiResSampleRateHz) {
                return@run false
            }

            true
        }

        val verdict = OutputConfiguration.Verdict(
            hiRes = isHiRes,
            potentialIssues = potentialIssues,
        )

        return OutputConfiguration(
            source = source,
            transcoding = transcoding,
            device = device,
            verdict = verdict,
        )
    }

    private fun Format.guessEncoding(): OutputConfiguration.Encoding? {
        encodingFromMedia3Encoding(pcmEncoding)?.let {
            return it
        }

        listOfNotNull(
            MimeTypes.getAudioMediaMimeType(codecs),
            sampleMimeType,
            containerMimeType,
        ).forEach { mimeType ->
            val encoding = MimeTypes.getEncoding(mimeType, codecs)
            encodingFromMedia3Encoding(encoding)?.let {
                return it
            }
        }

        return null
    }

    private fun Format.getStreamMimeType() = MimeTypes.getAudioMediaMimeType(
        codecs
    ) ?: sampleMimeType ?: containerMimeType

    private fun encodingFromMedia3Encoding(
        media3Encoding: @C.Encoding Int,
    ) = when (media3Encoding) {
        Format.NO_VALUE, C.ENCODING_INVALID -> null

        C.ENCODING_PCM_8BIT -> OutputConfiguration.Encoding.PCM_8_BIT
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> OutputConfiguration.Encoding.PCM_16_BIT

        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> OutputConfiguration.Encoding.PCM_24_BIT

        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN -> OutputConfiguration.Encoding.PCM_32_BIT

        C.ENCODING_PCM_FLOAT -> OutputConfiguration.Encoding.PCM_FLOAT
        C.ENCODING_MP3 -> OutputConfiguration.Encoding.MP3
        C.ENCODING_AAC_LC -> OutputConfiguration.Encoding.AAC_LC
        C.ENCODING_AAC_HE_V1 -> OutputConfiguration.Encoding.AAC_HE_V1
        C.ENCODING_AAC_HE_V2 -> OutputConfiguration.Encoding.AAC_HE_V2
        C.ENCODING_AAC_XHE -> OutputConfiguration.Encoding.AAC_XHE
        C.ENCODING_AAC_ELD -> OutputConfiguration.Encoding.AAC_ELD
        C.ENCODING_AAC_ER_BSAC -> OutputConfiguration.Encoding.AAC_ER_BSAC
        C.ENCODING_AC3 -> OutputConfiguration.Encoding.AC3
        C.ENCODING_E_AC3 -> OutputConfiguration.Encoding.E_AC3
        C.ENCODING_E_AC3_JOC -> OutputConfiguration.Encoding.E_AC3_JOC
        C.ENCODING_AC4 -> OutputConfiguration.Encoding.AC4
        C.ENCODING_DTS -> OutputConfiguration.Encoding.DTS
        C.ENCODING_DTS_HD -> OutputConfiguration.Encoding.DTS_HD
        C.ENCODING_DOLBY_TRUEHD -> OutputConfiguration.Encoding.DOLBY_TRUEHD
        C.ENCODING_OPUS -> OutputConfiguration.Encoding.OPUS
        C.ENCODING_DTS_UHD_P2 -> OutputConfiguration.Encoding.DTS_UHD_P2

        else -> {
            Log.e(LOG_TAG, "Unknown encoding: $media3Encoding")
            null
        }
    }

    private fun encodingFromAudioFormatEncoding(
        audioFormatEncoding: Int,
    ) = when (audioFormatEncoding) {
        AudioFormat.ENCODING_INVALID,
        AudioFormat.ENCODING_DEFAULT -> null

        AudioFormat.ENCODING_PCM_16BIT -> OutputConfiguration.Encoding.PCM_16_BIT
        AudioFormat.ENCODING_PCM_8BIT -> OutputConfiguration.Encoding.PCM_8_BIT
        AudioFormat.ENCODING_PCM_FLOAT -> OutputConfiguration.Encoding.PCM_FLOAT
        AudioFormat.ENCODING_AC3 -> OutputConfiguration.Encoding.AC3
        AudioFormat.ENCODING_E_AC3 -> OutputConfiguration.Encoding.E_AC3
        AudioFormat.ENCODING_DTS -> OutputConfiguration.Encoding.DTS
        AudioFormat.ENCODING_DTS_HD -> OutputConfiguration.Encoding.DTS_HD
        AudioFormat.ENCODING_MP3 -> OutputConfiguration.Encoding.MP3
        AudioFormat.ENCODING_AAC_LC -> OutputConfiguration.Encoding.AAC_LC
        AudioFormat.ENCODING_AAC_HE_V1 -> OutputConfiguration.Encoding.AAC_HE_V1
        AudioFormat.ENCODING_AAC_HE_V2 -> OutputConfiguration.Encoding.AAC_HE_V2
        AudioFormat.ENCODING_IEC61937 -> OutputConfiguration.Encoding.IEC61937
        AudioFormat.ENCODING_DOLBY_TRUEHD -> OutputConfiguration.Encoding.DOLBY_TRUEHD
        AudioFormat.ENCODING_AAC_ELD -> OutputConfiguration.Encoding.AAC_ELD
        AudioFormat.ENCODING_AAC_XHE -> OutputConfiguration.Encoding.AAC_XHE
        AudioFormat.ENCODING_AC4 -> OutputConfiguration.Encoding.AC4
        AudioFormat.ENCODING_E_AC3_JOC -> OutputConfiguration.Encoding.E_AC3_JOC
        AudioFormat.ENCODING_DOLBY_MAT -> OutputConfiguration.Encoding.DOLBY_MAT
        AudioFormat.ENCODING_OPUS -> OutputConfiguration.Encoding.OPUS
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputConfiguration.Encoding.PCM_24_BIT_PACKED
        AudioFormat.ENCODING_PCM_32BIT -> OutputConfiguration.Encoding.PCM_32_BIT
        AudioFormat.ENCODING_MPEGH_BL_L3 -> OutputConfiguration.Encoding.MPEGH_BL_L3
        AudioFormat.ENCODING_MPEGH_BL_L4 -> OutputConfiguration.Encoding.MPEGH_BL_L4
        AudioFormat.ENCODING_MPEGH_LC_L3 -> OutputConfiguration.Encoding.MPEGH_LC_L3
        AudioFormat.ENCODING_MPEGH_LC_L4 -> OutputConfiguration.Encoding.MPEGH_LC_L4
        //AudioFormat.ENCODING_DTS_UHD -> OutputConfiguration.Encoding.DTS_UHD_P1
        AudioFormat.ENCODING_DRA -> OutputConfiguration.Encoding.DRA
        AudioFormat.ENCODING_DTS_HD_MA -> OutputConfiguration.Encoding.DTS_HD_MA
        AudioFormat.ENCODING_DTS_UHD_P1 -> OutputConfiguration.Encoding.DTS_UHD_P1
        AudioFormat.ENCODING_DTS_UHD_P2 -> OutputConfiguration.Encoding.DTS_UHD_P2
        AudioFormat.ENCODING_DSD -> OutputConfiguration.Encoding.DSD

        else -> {
            Log.e(LOG_TAG, "Unknown encoding: $audioFormatEncoding")
            null
        }
    }

    private fun outputModeFromAudioTrackConfig(
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) = when {
        audioTrackConfig.offload -> OutputMode.OFFLOAD

        else -> when (audioTrackConfig.encoding) {
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> OutputMode.PCM

            else -> OutputMode.PASSTHROUGH
        }
    }
}
