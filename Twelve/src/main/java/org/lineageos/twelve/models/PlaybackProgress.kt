/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.models

/**
 * Playback progress.
 *
 * @param isPlaying Whether playback is in progress
 * @param playbackSpeed The current playback speed
 */
data class PlaybackProgress(
    val isPlaying: Boolean,
    val playbackSpeed: Float,
)
