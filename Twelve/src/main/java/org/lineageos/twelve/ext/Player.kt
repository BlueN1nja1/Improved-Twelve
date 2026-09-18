/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ext

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.lineageos.twelve.models.PlaybackProgress
import org.lineageos.twelve.models.PlaybackState
import org.lineageos.twelve.models.QueueItem
import org.lineageos.twelve.models.RepeatMode

fun Player.eventsFlow() = conflatedCallbackFlow {
    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            trySend(events)
        }
    }

    addListener(listener)

    awaitClose {
        removeListener(listener)
    }
}

fun Player.mediaMetadataFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_MEDIA_METADATA_CHANGED) }
    .map { mediaMetadata }
    .onStart { emit(mediaMetadata) }

fun Player.mediaItemFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION) }
    .map { currentMediaItem }
    .onStart { emit(currentMediaItem) }

fun Player.playbackStateFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED) }
    .map { typedPlaybackState }
    .onStart { emit(typedPlaybackState) }

fun Player.isPlayingFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_IS_PLAYING_CHANGED) }
    .map { isPlaying }
    .onStart { emit(isPlaying) }

fun Player.shuffleModeFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) }
    .map { shuffleModeEnabled }
    .onStart { emit(shuffleModeEnabled) }

fun Player.repeatModeFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_REPEAT_MODE_CHANGED) }
    .map { typedRepeatMode }
    .onStart { emit(typedRepeatMode) }

fun Player.playbackParametersFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) }
    .map { playbackParameters }
    .onStart { emit(playbackParameters) }

fun Player.availableCommandsFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) }
    .map { availableCommands }
    .onStart { emit(availableCommands) }

fun Player.tracksFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter { it.containsAny(Player.EVENT_TRACKS_CHANGED) }
    .map { currentTracks }
    .onStart { emit(currentTracks) }

private fun Player.mediaItemsShuffled() =
    currentTimeline.getFirstWindowIndex(shuffleModeEnabled).takeIf {
        it != C.INDEX_UNSET
    }?.let { startIndex ->
        var index = startIndex
        buildList {
            repeat(currentTimeline.windowCount) {
                add(getMediaItemAt(index))
                index = currentTimeline.getNextWindowIndex(
                    index, Player.REPEAT_MODE_OFF, shuffleModeEnabled
                )
            }
        }.let { items ->
            items.indexOfFirst { getMediaItemAt(currentMediaItemIndex) == it } to items
        }
    } ?: (currentMediaItemIndex to mediaItems)

private fun Player.createQueueItems() =
    mediaItemsShuffled().let { (currentIndex, mediaItems) ->
        mediaItems.mapIndexed { index, mediaItem ->
            QueueItem(
                mediaItem = mediaItem,
                isCurrent = index == currentIndex
            )
        }
    }

fun Player.queueFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter {
        it.containsAny(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
        )
    }
    .map { createQueueItems() }
    .onStart { emit(createQueueItems()) }

private fun Player.createPlaybackProgress() = PlaybackProgress(
    isPlaying = isPlaying,
    playbackSpeed = playbackParameters.speed,
)

fun Player.playbackProgressFlow(eventsFlow: Flow<Player.Events>) = eventsFlow
    .filter {
        it.containsAny(
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_TIMELINE_CHANGED,
        )
    }
    .map { createPlaybackProgress() }
    .onStart { emit(createPlaybackProgress()) }

var Player.typedRepeatMode: RepeatMode
    get() = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> RepeatMode.NONE
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> throw Exception("Unknown repeat mode")
    }
    set(value) {
        repeatMode = when (value) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

val Player.typedPlaybackState: PlaybackState
    get() = when (playbackState) {
        Player.STATE_IDLE -> PlaybackState.IDLE
        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        Player.STATE_READY -> PlaybackState.READY
        Player.STATE_ENDED -> PlaybackState.ENDED
        else -> throw Exception("Unknown playback state")
    }

val Player.mediaItems: List<MediaItem>
    get() = (0 until mediaItemCount).map {
        getMediaItemAt(it)
    }

@OptIn(UnstableApi::class)
fun Player.setOffloadEnabled(enabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences
                .Builder()
                .setAudioOffloadMode(
                    if (enabled) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    }
                )
                .build()
        ).build()
}
