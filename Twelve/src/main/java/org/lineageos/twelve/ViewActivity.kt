/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.IntentsViewModel
import org.lineageos.twelve.viewmodels.LocalPlayerViewModel
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.reflect.safeCast

/**
 * An activity used to handle view intents.
 */
class ViewActivity : AppCompatActivity(R.layout.activity_view) {
    // View models
    private val intentsViewModel by viewModels<IntentsViewModel>()
    private val localPlayerViewModel by viewModels<LocalPlayerViewModel>()

    // Views
    private val albumTitleTextView by lazy { findViewById<TextView>(R.id.albumTitleTextView) }
    private val artistNameTextView by lazy { findViewById<TextView>(R.id.artistNameTextView) }
    private val audioTitleTextView by lazy { findViewById<TextView>(R.id.audioTitleTextView) }
    private val currentTimestampTextView by lazy { findViewById<TextView>(R.id.currentTimestampTextView) }
    private val dummyThumbnailImageView by lazy { findViewById<ImageView>(R.id.dummyThumbnailImageView) }
    private val durationTimestampTextView by lazy { findViewById<TextView>(R.id.durationTimestampTextView) }
    private val nextTrackMaterialButton by lazy { findViewById<MaterialButton>(R.id.nextTrackMaterialButton) }
    private val playPauseMaterialButton by lazy { findViewById<MaterialButton>(R.id.playPauseMaterialButton) }
    private val playbackSpeedMaterialButton by lazy { findViewById<MaterialButton>(R.id.playbackSpeedMaterialButton) }
    private val previousTrackMaterialButton by lazy { findViewById<MaterialButton>(R.id.previousTrackMaterialButton) }
    private val progressSlider by lazy { findViewById<Slider>(R.id.progressSlider) }
    private val repeatMarkerImageView by lazy { findViewById<ImageView>(R.id.repeatMarkerImageView) }
    private val repeatMaterialButton by lazy { findViewById<MaterialButton>(R.id.repeatMaterialButton) }
    private val shuffleMarkerImageView by lazy { findViewById<ImageView>(R.id.shuffleMarkerImageView) }
    private val shuffleMaterialButton by lazy { findViewById<MaterialButton>(R.id.shuffleMaterialButton) }
    private val thumbnailImageView by lazy { findViewById<ImageView>(R.id.thumbnailImageView) }

    // Progress slider state
    private var isProgressSliderDragging = false

    // Intents
    private val intentListener = Consumer<Intent> { intentsViewModel.onIntent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    localPlayerViewModel.seekToPosition(slider.value.roundToLong())
                }
            }
        )

        playPauseMaterialButton.setOnClickListener {
            localPlayerViewModel.togglePlayPause()
        }

        playbackSpeedMaterialButton.setOnClickListener {
            localPlayerViewModel.shufflePlaybackSpeed()
        }

        repeatMaterialButton.setOnClickListener {
            localPlayerViewModel.toggleRepeatMode()
        }

        shuffleMaterialButton.setOnClickListener {
            localPlayerViewModel.toggleShuffleMode()
        }

        previousTrackMaterialButton.setOnClickListener {
            localPlayerViewModel.seekToPrevious()
        }

        nextTrackMaterialButton.setOnClickListener {
            localPlayerViewModel.seekToNext()
        }

        intentsViewModel.onIntent(intent)
        addOnNewIntentListener(intentListener)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    localPlayerViewModel.mediaMetadata.collectLatest { mediaMetadata ->
                        mediaMetadata.albumTitle?.also {
                            if (albumTitleTextView.text != it) {
                                albumTitleTextView.text = it
                            }
                            albumTitleTextView.isVisible = true
                        } ?: run {
                            albumTitleTextView.isVisible = false
                        }

                        mediaMetadata.artist?.also {
                            if (artistNameTextView.text != it) {
                                artistNameTextView.text = it
                            }
                            artistNameTextView.isVisible = true
                        } ?: run {
                            artistNameTextView.isVisible = false
                        }

                        mediaMetadata.title?.also {
                            if (audioTitleTextView.text != it) {
                                audioTitleTextView.text = it
                            }
                            audioTitleTextView.isVisible = true
                        } ?: run {
                            audioTitleTextView.isVisible = false
                        }
                    }
                }

                launch {
                    localPlayerViewModel.isPlaying.collectLatest { isPlaying ->
                        playPauseMaterialButton.setIconResource(
                            when (isPlaying) {
                                true -> R.drawable.avd_play_to_pause
                                false -> R.drawable.avd_pause_to_play
                            }
                        )
                        AnimatedVectorDrawable::class.safeCast(
                            playPauseMaterialButton.icon
                        )?.start()
                    }
                }

                launch {
                    localPlayerViewModel.mediaArtwork.collectLatest {
                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                val thumbnail = it.data

                                thumbnailImageView.loadThumbnail(
                                    thumbnail,
                                    placeholder = R.drawable.ic_music_note,
                                )
                                thumbnailImageView.isVisible = true
                                dummyThumbnailImageView.isVisible = false
                            }

                            is FlowResult.Failure -> {
                                Log.e(LOG_TAG, "Failed to load artwork")
                                dummyThumbnailImageView.isVisible = true
                                thumbnailImageView.isVisible = false
                            }
                        }
                    }
                }

                launch {
                    localPlayerViewModel.durationCurrentPositionMs.collectLatest {
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
                    localPlayerViewModel.playbackParameters.collectLatest {
                        playbackSpeedMaterialButton.text = getString(
                            R.string.playback_speed_format,
                            playbackSpeedFormatter.format(it.speed),
                        )
                    }
                }

                launch {
                    localPlayerViewModel.repeatMode.collectLatest {
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
                    localPlayerViewModel.shuffleMode.collectLatest { shuffleModeEnabled ->
                        shuffleMarkerImageView.isVisible = shuffleModeEnabled
                    }
                }

                launch {
                    intentsViewModel.parsedIntent.collectLatest { parsedIntent ->
                        parsedIntent?.handle {
                            if (it.action != IntentsViewModel.ParsedIntent.Action.VIEW) {
                                Log.e(LOG_TAG, "Cannot handle action ${it.action}")
                                finish()
                                return@handle
                            }

                            if (it.contents.isEmpty()) {
                                Log.e(LOG_TAG, "No content to play")
                                finish()
                                return@handle
                            }

                            val contentType = it.contents.first().type
                            if (contentType != MediaType.AUDIO) {
                                Log.e(LOG_TAG, "Cannot handle content type $contentType")
                                finish()
                                return@handle
                            }

                            if (it.contents.any { content -> content.type != contentType }) {
                                Log.e(LOG_TAG, "All contents must have the same type")
                                finish()
                                return@handle
                            }

                            localPlayerViewModel.setMediaUris(
                                it.contents.map { content -> content.uri }
                            )
                        }
                    }
                }

                launch {
                    localPlayerViewModel.availableCommands.collectLatest {
                        playPauseMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_PLAY_PAUSE
                        )

                        playbackSpeedMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SPEED_AND_PITCH
                        )

                        shuffleMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SHUFFLE_MODE
                        )

                        repeatMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_REPEAT_MODE
                        )

                        previousTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_PREVIOUS
                        )

                        nextTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_NEXT
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = ViewActivity::class.simpleName!!

        private val decimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT)

        private val playbackSpeedFormatter = DecimalFormat("0.#", decimalFormatSymbols)
    }
}
