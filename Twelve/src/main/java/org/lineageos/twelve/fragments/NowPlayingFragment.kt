/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.content.Intent
import android.graphics.PixelFormat
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.bogerchan.niervisualizer.NierVisualizerManager
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.FlowResult.Companion.getOrNull
import org.lineageos.twelve.models.OutputConfiguration
import org.lineageos.twelve.models.PlaybackState
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.ui.visualizer.VisualizerNVDataSource
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.NowPlayingViewModel
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Now playing fragment.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {
    // View models
    private val viewModel by viewModels<NowPlayingViewModel>()

    // Views
    private val albumArtConstraintLayout by getViewProperty<ConstraintLayout?>(R.id.albumArtConstraintLayout)
    private val albumArtImageView by getViewProperty<ImageView>(R.id.albumArtImageView)
    private val albumTitleTextView by getViewProperty<TextView>(R.id.albumTitleTextView)
    private val audioInformationMaterialButton by getViewProperty<MaterialButton>(R.id.audioInformationMaterialButton)
    private val audioTitleTextView by getViewProperty<TextView>(R.id.audioTitleTextView)
    private val artistNameTextView by getViewProperty<TextView>(R.id.artistNameTextView)
    private val currentLyricsTextView by getViewProperty<TextView>(R.id.currentLyricsTextView)
    private val currentTimestampTextView by getViewProperty<TextView>(R.id.currentTimestampTextView)
    private val durationTimestampTextView by getViewProperty<TextView>(R.id.durationTimestampTextView)
    private val equalizerMaterialButton by getViewProperty<MaterialButton>(R.id.equalizerMaterialButton)
    private val fileTypeTextView by getViewProperty<TextView>(R.id.fileTypeTextView)
    private val hiResImageView by getViewProperty<ImageView>(R.id.hiResImageView)
    private val isFavoriteMaterialButton by getViewProperty<MaterialButton>(R.id.isFavoriteMaterialButton)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val lyricsMaterialCardView by getViewProperty<MaterialCardView>(R.id.lyricsMaterialCardView)
    private val nestedScrollView by getViewProperty<NestedScrollView>(R.id.nestedScrollView)
    private val nextLyricsTextView by getViewProperty<TextView>(R.id.nextLyricsTextView)
    private val nextTrackMaterialButton by getViewProperty<MaterialButton>(R.id.nextTrackMaterialButton)
    private val outputDeviceImageView by getViewProperty<ImageView>(R.id.outputDeviceImageView)
    private val playPauseMaterialButton by getViewProperty<MaterialButton>(R.id.playPauseMaterialButton)
    private val playbackSpeedMaterialButton by getViewProperty<MaterialButton>(R.id.playbackSpeedMaterialButton)
    private val potentialIssuesImageView by getViewProperty<ImageView>(R.id.potentialIssuesImageView)
    private val previousLyricsTextView by getViewProperty<TextView>(R.id.previousLyricsTextView)
    private val previousTrackMaterialButton by getViewProperty<MaterialButton>(R.id.previousTrackMaterialButton)
    private val progressSlider by getViewProperty<Slider>(R.id.progressSlider)
    private val queueMaterialButton by getViewProperty<MaterialButton>(R.id.queueMaterialButton)
    private val repeatMarkerImageView by getViewProperty<ImageView>(R.id.repeatMarkerImageView)
    private val repeatMaterialButton by getViewProperty<MaterialButton>(R.id.repeatMaterialButton)
    private val showLyricsMaterialButton by getViewProperty<MaterialButton>(R.id.showLyricsMaterialButton)
    private val shuffleMarkerImageView by getViewProperty<ImageView>(R.id.shuffleMarkerImageView)
    private val shuffleMaterialButton by getViewProperty<MaterialButton>(R.id.shuffleMaterialButton)
    private val statsMaterialCardView by getViewProperty<MaterialCardView>(R.id.statsMaterialCardView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val visualizerMaterialButton by getViewProperty<MaterialButton>(R.id.visualizerMaterialButton)
    private val visualizerSurfaceView by getViewProperty<SurfaceView>(R.id.visualizerSurfaceView)

    // Progress slider state
    private var isProgressSliderDragging = false

    // AudioFX
    private val audioEffectsStartForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Empty
        }

    // Visualizer
    private val visualizerManager = NierVisualizerManager()
    private val visualizerNVDataSource by lazy { VisualizerNVDataSource() }
    private var isVisualizerStarted = false

    // Permissions
    private val visualizerPermissionsChecker = PermissionsChecker(
        this,
        PermissionsUtils.visualizerPermissions,
        true,
        R.string.visualizer_permissions_toast,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        visualizerManager.init(visualizerNVDataSource)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = true,
                end = true,
            )

            windowInsets
        }

        albumArtConstraintLayout?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                v.updatePadding(
                    insets,
                    bottom = true,
                )

                windowInsets
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(nestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        // Top bar
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        statsMaterialCardView.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_nowPlayingFragment_to_fragment_now_playing_stats_dialog
            )
        }

        // Visualizer
        visualizerSurfaceView.setZOrderOnTop(true)
        visualizerSurfaceView.holder.setFormat(PixelFormat.TRANSPARENT)

        // Audio information
        audioTitleTextView.isSelected = true
        artistNameTextView.isSelected = true
        albumTitleTextView.isSelected = true

        // Media controls
        progressSlider.setLabelFormatter {
            TimestampFormatter.formatTimestampMillis(it)
        }
        progressSlider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    isProgressSliderDragging = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    isProgressSliderDragging = false
                    viewModel.seekToPosition(slider.value.roundToLong())
                }
            }
        )

        shuffleMaterialButton.setOnClickListener {
            viewModel.toggleShuffleMode()
        }

        previousTrackMaterialButton.setOnClickListener {
            viewModel.seekToPrevious()
        }

        playPauseMaterialButton.setOnClickListener {
            viewModel.togglePlayPause()
        }

        nextTrackMaterialButton.setOnClickListener {
            viewModel.seekToNext()
        }

        repeatMaterialButton.setOnClickListener {
            viewModel.toggleRepeatMode()
        }

        // Bottom bar buttons
        playbackSpeedMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_nowPlayingFragment_to_fragment_playback_control_bottom_sheet_dialog
            )
        }

        audioInformationMaterialButton.setOnClickListener {
            viewModel.audio.value.getOrNull()?.let {
                findNavController().navigateSafe(
                    R.id.action_nowPlayingFragment_to_fragment_media_item_bottom_sheet_dialog,
                    MediaItemBottomSheetDialogFragment.createBundle(
                        it.uri,
                        fromNowPlaying = true,
                    )
                )
            }
        }

        equalizerMaterialButton.setOnClickListener {
            // Open system equalizer
            viewModel.audioSessionId.value?.let { audioSessionId ->
                audioEffectsStartForResult.launch(
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    },
                    null
                )
            }
        }

        visualizerMaterialButton.setOnClickListener {
            viewModel.nextVisualizerType()
        }

        queueMaterialButton.setOnClickListener {
            findNavController().navigateSafe(R.id.action_nowPlayingFragment_to_fragment_queue)
        }

        isFavoriteMaterialButton.setOnClickListener {
            lifecycleScope.launch {
                isFavoriteMaterialButton.isEnabled = false
                viewModel.toggleFavorites()
                isFavoriteMaterialButton.isEnabled = true
            }
        }

        // Lyrics
        showLyricsMaterialButton.setOnClickListener {
            findNavController().navigateSafe(R.id.action_nowPlayingFragment_to_fragment_lyrics)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        playPauseMaterialButton.setIconResource(
                            when (isPlaying) {
                                true -> R.drawable.ic_pause
                                false -> R.drawable.ic_play_arrow
                            }
                        )
                    }
                }

                launch {
                    viewModel.playbackState.collectLatest {
                        linearProgressIndicator.isVisible = it == PlaybackState.BUFFERING
                    }
                }

                launch {
                    viewModel.audio.collectLatest {
                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                val audio = it.data

                                isFavoriteMaterialButton.isVisible = true
                                isFavoriteMaterialButton.setIconResource(
                                    when (audio.isFavorite) {
                                        true -> R.drawable.ic_heart_filled
                                        false -> R.drawable.ic_heart_unfilled
                                    }
                                )
                                isFavoriteMaterialButton.tooltipText = getString(
                                    when (audio.isFavorite) {
                                        true -> R.string.remove_from_favorites
                                        false -> R.string.add_to_favorites
                                    }
                                )
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Error while loading audio, error: ${it.error}",
                                    it.throwable
                                )

                                isFavoriteMaterialButton.isVisible = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.mediaMetadata.collectLatest { mediaMetadata ->
                        val audioTitle = mediaMetadata.displayTitle
                            ?: mediaMetadata.title
                            ?: getString(R.string.unknown)
                        if (audioTitleTextView.text != audioTitle) {
                            audioTitleTextView.text = audioTitle
                        }

                        val artistName = mediaMetadata.artist
                            ?: getString(R.string.artist_unknown)
                        if (artistNameTextView.text != artistName) {
                            artistNameTextView.text = artistName
                        }

                        val albumTitle = mediaMetadata.albumTitle
                            ?: getString(R.string.album_unknown)
                        if (albumTitleTextView.text != albumTitle) {
                            albumTitleTextView.text = albumTitle
                        }
                    }
                }

                launch {
                    viewModel.mediaArtwork.collectLatest {
                        when (it) {
                            null -> {
                                // Do nothing
                            }

                            is Result.Success -> {
                                albumArtImageView.loadThumbnail(
                                    it.data,
                                    placeholder = R.drawable.ic_music_note,
                                )
                            }

                            is Result.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Error while getting media artwork: ${it.error}",
                                    it.throwable
                                )
                            }
                        }
                    }
                }

                launch {
                    viewModel.playbackParameters.collectLatest {
                        playbackSpeedMaterialButton.text = getString(
                            R.string.playback_speed_format,
                            playbackSpeedFormatter.format(it.speed),
                        )
                    }
                }

                launch {
                    viewModel.displayFileType.collectLatest { displayFileType ->
                        fileTypeTextView.text = displayFileType ?: getString(
                            R.string.audio_file_type_unknown_short
                        )
                    }
                }

                launch {
                    viewModel.outputConfiguration.collectLatest { outputConfiguration ->
                        outputConfiguration?.device?.type?.also {
                            val deviceDrawableResId = when (it) {
                                OutputConfiguration.Device.Type.BUILTIN ->
                                    R.drawable.ic_mobile_speaker

                                OutputConfiguration.Device.Type.HEADPHONES ->
                                    R.drawable.ic_headphones

                                OutputConfiguration.Device.Type.EXTERNAL_SPEAKERS ->
                                    R.drawable.ic_speaker_group

                                OutputConfiguration.Device.Type.BLUETOOTH ->
                                    R.drawable.ic_bluetooth

                                OutputConfiguration.Device.Type.HDMI ->
                                    R.drawable.ic_settings_input_hdmi

                                OutputConfiguration.Device.Type.USB ->
                                    R.drawable.ic_usb

                                OutputConfiguration.Device.Type.REMOTE ->
                                    R.drawable.ic_cast

                                OutputConfiguration.Device.Type.HEARING_AID ->
                                    R.drawable.ic_hearing_aid
                            }
                            outputDeviceImageView.setImageResource(deviceDrawableResId)
                            outputDeviceImageView.isVisible = true
                        } ?: run {
                            outputDeviceImageView.isVisible = false
                        }

                        hiResImageView.isVisible = outputConfiguration?.verdict?.hiRes == true

                        potentialIssuesImageView.isVisible =
                            outputConfiguration?.verdict?.potentialIssues?.isNotEmpty() == true
                    }
                }

                launch {
                    viewModel.repeatMode.collectLatest {
                        repeatMaterialButton.setIconResource(
                            when (it) {
                                RepeatMode.NONE,
                                RepeatMode.ALL -> R.drawable.ic_repeat

                                RepeatMode.ONE -> R.drawable.ic_repeat_one
                            }
                        )
                        repeatMarkerImageView.isVisible = it != RepeatMode.NONE
                    }
                }

                launch {
                    viewModel.shuffleMode.collectLatest { shuffleModeEnabled ->
                        shuffleMarkerImageView.isVisible = shuffleModeEnabled
                    }
                }

                launch {
                    viewModel.durationCurrentPositionMs.collectLatest {
                        val durationMs = it.first ?: 0L
                        val currentPositionMs = it.second ?: 0L

                        val newValueTo = durationMs.toFloat().takeIf { it > 0 } ?: 1f
                        val newValue = currentPositionMs.toFloat()

                        progressSlider.valueTo = newValueTo

                        if (!isProgressSliderDragging) {
                            progressSlider.value = newValue
                        }

                        currentTimestampTextView.text =
                            TimestampFormatter.formatTimestampMillis(currentPositionMs)
                    }
                }

                launch {
                    viewModel.availableCommands.collectLatest {
                        shuffleMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SHUFFLE_MODE
                        )

                        previousTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_PREVIOUS
                        )

                        playPauseMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_PLAY_PAUSE
                        )

                        nextTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_NEXT
                        )

                        repeatMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_REPEAT_MODE
                        )

                        playbackSpeedMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SPEED_AND_PITCH
                        )
                    }
                }

                launch {
                    viewModel.audioSessionId.collectLatest {
                        visualizerNVDataSource.setAudioSessionId(it)
                    }
                }

                launch {
                    viewModel.isVisualizerEnabled.collectLatest { isVisualizerEnabled ->
                        visualizerSurfaceView.isVisible = isVisualizerEnabled

                        if (!isVisualizerEnabled) {
                            return@collectLatest
                        }

                        visualizerPermissionsChecker.withPermissionsGranted {
                            launch {
                                visualizerNVDataSource.workFlow.collect()
                            }

                            launch {
                                viewModel.currentVisualizerType.collectLatest {
                                    it.factory.invoke()?.let { renderers ->
                                        visualizerManager.start(visualizerSurfaceView, renderers)
                                        isVisualizerStarted = true
                                    } ?: run {
                                        if (isVisualizerStarted) {
                                            visualizerManager.stop()
                                            isVisualizerStarted = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.lyricsLines.collectLatest {
                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                val (lyrics, currentIndex) = it.data

                                val index = currentIndex ?: 0

                                val previousLyrics = lyrics.getOrNull(index - 1)
                                val currentLyrics = lyrics.getOrNull(index)
                                val nextLyrics = lyrics.getOrNull(index + 1)

                                previousLyricsTextView.text = previousLyrics?.first?.text
                                currentLyricsTextView.text = currentLyrics?.first?.text
                                nextLyricsTextView.text = nextLyrics?.first?.text

                                lyricsMaterialCardView.isVisible = true
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Error while loading lyrics: ${it.error}",
                                    it.throwable
                                )

                                lyricsMaterialCardView.isVisible = false
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isVisualizerStarted) {
            visualizerManager.resume()
        }
    }

    override fun onPause() {
        if (isVisualizerStarted) {
            visualizerManager.pause()
        }

        super.onPause()
    }

    override fun onDestroyView() {
        if (isVisualizerStarted) {
            visualizerManager.stop()
        }
        isVisualizerStarted = false

        super.onDestroyView()
    }

    override fun onDestroy() {
        visualizerManager.release()

        super.onDestroy()
    }

    companion object {
        private val LOG_TAG = NowPlayingFragment::class.simpleName!!

        private val decimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT)

        private val playbackSpeedFormatter = DecimalFormat("0.#", decimalFormatSymbols)
    }
}
