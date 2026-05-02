/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.media3.common.MediaMetadata
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.models.Thumbnail
import kotlin.reflect.safeCast

class NowPlayingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val artistNameTextView by lazy { findViewById<TextView>(R.id.artistNameTextView) }
    private val albumTitleTextView by lazy { findViewById<TextView>(R.id.albumTitleTextView) }
    private val circularProgressIndicator by lazy { findViewById<CircularProgressIndicator>(R.id.circularProgressIndicator) }
    private val playPauseMaterialButton by lazy { findViewById<MaterialButton>(R.id.playPauseMaterialButton) }
    private val thumbnailImageView by lazy { findViewById<ImageView>(R.id.thumbnailImageView) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }

    init {
        setCardBackgroundColor(
            MaterialColors.getColorStateList(
                context,
                com.google.android.material.R.attr.colorSurfaceContainer,
                cardBackgroundColor,
            )
        )
        cardElevation = 0f
        radius = 0f
        strokeWidth = 0

        inflate(context, R.layout.now_playing_bar, this)

        circularProgressIndicator.min = 0
    }

    fun setOnPlayPauseClickListener(l: OnClickListener?) =
        playPauseMaterialButton.setOnClickListener(l)

    fun updateIsPlaying(isPlaying: Boolean) {
        playPauseMaterialButton.setIconResource(
            when (isPlaying) {
                true -> R.drawable.avd_play_to_pause
                false -> R.drawable.avd_pause_to_play
            }
        )
        AnimatedVectorDrawable::class.safeCast(playPauseMaterialButton.icon)?.start()
    }

    fun updateMediaMetadata(mediaMetadata: MediaMetadata) {
        val audioTitle = mediaMetadata.displayTitle
            ?: mediaMetadata.title
            ?: context.getString(R.string.unknown)
        if (titleTextView.text != audioTitle) {
            titleTextView.text = audioTitle
        }
        titleTextView.isSelected = true

        val artistName = mediaMetadata.artist
            ?: context.getString(R.string.artist_unknown)
        if (artistNameTextView.text != artistName) {
            artistNameTextView.text = artistName
        }
        artistNameTextView.isSelected = true

        val albumTitle = mediaMetadata.albumTitle
            ?: context.getString(R.string.album_unknown)
        if (albumTitleTextView.text != albumTitle) {
            albumTitleTextView.text = albumTitle
        }
        albumTitleTextView.isSelected = true
    }

    fun updateMediaArtwork(artwork: Thumbnail?) {
        thumbnailImageView.loadThumbnail(artwork, placeholder = R.drawable.ic_music_note)
    }

    fun updateDurationCurrentPositionMs(durationMs: Long?, currentPositionMs: Long?) {
        val currentPositionSecs = currentPositionMs?.let { it / 1000 }?.toInt() ?: 0
        val durationSecs = durationMs?.let { it / 1000 }?.toInt()?.takeIf { it != 0 } ?: 1

        circularProgressIndicator.max = durationSecs
        circularProgressIndicator.setProgressCompat(currentPositionSecs, true)
    }
}
