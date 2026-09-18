/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Resources
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.listen
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.twelve.MainActivity
import org.lineageos.twelve.R
import org.lineageos.twelve.TwelveApplication
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.enableFloatOutput
import org.lineageos.twelve.ext.enableOffload
import org.lineageos.twelve.ext.mapAsync
import org.lineageos.twelve.ext.mediaItems
import org.lineageos.twelve.ext.next
import org.lineageos.twelve.ext.skipSilence
import org.lineageos.twelve.ext.stopPlaybackOnTaskRemoved
import org.lineageos.twelve.ext.typedRepeatMode
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.ui.widgets.NowPlayingAppWidgetProvider

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    enum class CustomCommand {
        /**
         * Toggles audio offload mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable offload
         */
        TOGGLE_OFFLOAD,

        /**
         * Toggles skip silence.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable skip silence
         */
        TOGGLE_SKIP_SILENCE,

        /**
         * Get the audio session ID.
         *
         * Response:
         * - [CustomCommand.RSP_VALUE] ([Int]): The audio session ID
         */
        GET_AUDIO_SESSION_ID,

        /**
         * Toggle shuffle mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable shuffle mode
         */
        TOGGLE_SHUFFLE {
            override fun buildCommandButton(
                player: ExoPlayer,
                resources: Resources,
            ) = player.shuffleModeEnabled.let { shuffleModeEnabled ->
                val (icon, titleStringResId) = when (shuffleModeEnabled) {
                    true -> CommandButton.ICON_SHUFFLE_ON to R.string.shuffle_on
                    false -> CommandButton.ICON_SHUFFLE_OFF to R.string.shuffle_off
                }

                CommandButton.Builder(icon)
                    .setDisplayName(resources.getString(titleStringResId))
                    .setSessionCommand(
                        SessionCommand(
                            name,
                            Bundle {
                                putBoolean(ARG_VALUE, !shuffleModeEnabled)
                            },
                        )
                    )
                    .build()
            }
        },

        /**
         * Toggle repeat mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([String]): The repeat mode
         */
        TOGGLE_REPEAT {
            override fun buildCommandButton(
                player: ExoPlayer,
                resources: Resources,
            ) = player.typedRepeatMode.let { repeatMode ->
                val (icon, titleStringResId) = when (repeatMode) {
                    RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF to R.string.repeat_off
                    RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL to R.string.repeat_all
                    RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE to R.string.repeat_one
                }

                CommandButton.Builder(icon)
                    .setDisplayName(resources.getString(titleStringResId))
                    .setSessionCommand(
                        SessionCommand(
                            name,
                            Bundle {
                                putString(ARG_VALUE, repeatMode.next().name)
                            },
                        )
                    )
                    .build()
            }
        };

        val sessionCommand = SessionCommand(name, Bundle.EMPTY)

        open fun buildCommandButton(player: ExoPlayer, resources: Resources): CommandButton? = null

        companion object {
            const val ARG_VALUE = "value"
            const val RSP_VALUE = "value"

            fun fromCustomAction(
                customAction: String
            ) = entries.firstOrNull { it.name == customAction }

            suspend fun MediaController.sendCustomCommand(
                customCommand: CustomCommand,
                extras: Bundle
            ) = sendCustomCommand(customCommand.sessionCommand, extras).await()
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val mediaRepositoryTree by lazy {
        MediaRepositoryTree(
            applicationContext,
            mediaRepository,
            providersRepository,
        )
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private val resumptionPlaylistRepository by lazy {
        (application as TwelveApplication).resumptionPlaylistRepository
    }

    private val mediaRepository by lazy {
        (application as TwelveApplication).mediaRepository
    }

    private val providersRepository by lazy {
        (application as TwelveApplication).providersRepository
    }

    private val outputConfigurationRepository by lazy {
        (application as TwelveApplication).outputConfigurationRepository
    }

    private val analyticsListener by lazy {
        object : AnalyticsListener {
            override fun onEvents(player: Player, events: AnalyticsListener.Events) {
                if (events.contains(AnalyticsListener.EVENT_AUDIO_INPUT_FORMAT_CHANGED)) {
                    outputConfigurationRepository.updateFormat(
                        this@PlaybackService.player.audioFormat
                    )
                }
            }
        }
    }

    private val mediaLibrarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .apply {
                        for (command in CustomCommand.entries) {
                            add(command.sessionCommand)
                        }
                    }
                    .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            rating: Rating
        ) = lifecycleScope.future {
            val heartRating = rating as? HeartRating ?: return@future SessionResult(
                SessionError.ERROR_NOT_SUPPORTED
            )

            SessionResult(
                when (mediaRepositoryTree.setFavorite(mediaId, heartRating.isHeart)) {
                    true -> {
                        // Horrible.
                        player.mediaItems.forEachIndexed { index, mediaItem ->
                            if (mediaItem.mediaId == mediaId) {
                                player.replaceMediaItem(
                                    index,
                                    mediaItem.buildUpon()
                                        .setMediaMetadata(
                                            mediaItem.mediaMetadata.buildUpon()
                                                .setUserRating(heartRating)
                                                .build()
                                        )
                                        .build(),
                                )
                            }
                        }

                        SessionResult.RESULT_SUCCESS
                    }

                    false -> SessionError.ERROR_UNKNOWN
                }
            )
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating
        ) = player.currentMediaItem?.let {
            onSetRating(session, controller, it.mediaId, rating)
        } ?: Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ) = lifecycleScope.future {
            getResumptionPlaylist()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItem(mediaRepositoryTree.rootMediaItem, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ) = lifecycleScope.future {
            mediaRepositoryTree.getItem(mediaId)?.let {
                LibraryResult.ofItem(it, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItemList(mediaRepositoryTree.getChildren(parentId), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ) = lifecycleScope.future {
            mediaRepositoryTree.resolveMediaItems(mediaItems)
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            browser: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) = lifecycleScope.future {
            val resolvedMediaItems = mediaRepositoryTree.resolveMediaItems(mediaItems)

            launch {
                resumptionPlaylistRepository.onMediaItemsChanged(
                    resolvedMediaItems.map { it.mediaId },
                    startIndex,
                    startPositionMs,
                )
            }

            MediaSession.MediaItemsWithStartPosition(
                resolvedMediaItems,
                startIndex,
                startPositionMs
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            session.notifySearchResultChanged(
                browser, query, mediaRepositoryTree.search(query).size, params
            )
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItemList(mediaRepositoryTree.search(query), params)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ) = lifecycleScope.future {
            when (CustomCommand.fromCustomAction(customCommand.customAction)) {
                CustomCommand.TOGGLE_OFFLOAD -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setAudioOffloadPreferences(
                                    TrackSelectionParameters.AudioOffloadPreferences
                                        .Builder()
                                        .setAudioOffloadMode(
                                            getOffloadMode(it)
                                        )
                                        .build()
                                ).build()
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.TOGGLE_SKIP_SILENCE -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.skipSilenceEnabled = it
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.GET_AUDIO_SESSION_ID -> {
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle {
                            putInt(CustomCommand.RSP_VALUE, player.audioSessionId)
                        },
                    )
                }

                CustomCommand.TOGGLE_SHUFFLE -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.shuffleModeEnabled = it
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.TOGGLE_REPEAT -> {
                    args.getString(CustomCommand.ARG_VALUE)?.let {
                        player.typedRepeatMode = RepeatMode.valueOf(it)
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                null -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setRenderersFactory(
                TwelveRenderersFactory(
                    this,
                    sharedPreferences.enableFloatOutput,
                    onAudioDeviceInfoChanged = {
                        outputConfigurationRepository.updateAudioDeviceInfo(it)
                    },
                    onAudioTrackConfigChanged = {
                        outputConfigurationRepository.updateAudioTrackConfig(it)
                    },
                )
            )
            .setTrackSelector(DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                        .setAudioOffloadPreferences(
                            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                                .setAudioOffloadMode(
                                    getOffloadMode(sharedPreferences.enableOffload)
                                ).build()
                        )
                )
            })
            .setSkipSilenceEnabled(sharedPreferences.skipSilence)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(
            this, player, mediaLibrarySessionCallback
        )
            .setBitmapLoader(
                CoilBitmapLoader(
                    this,
                    lifecycleScope,
                    MediaSession.getBitmapDimensionLimit(this)
                )
            )
            .setSessionActivity(getSingleTopActivity())
            .setCustomLayout(getCustomLayout())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply {
                    setSmallIcon(R.drawable.ic_notification_small_icon)
                }
        )

        player.addAnalyticsListener(analyticsListener)

        lifecycleScope.launch {
            player.listen { events ->
                // Update startIndex and startPositionMs in resumption playlist.
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    lifecycleScope.launch {
                        resumptionPlaylistRepository.onPlaybackPositionChanged(
                            player.currentMediaItemIndex,
                            player.currentPosition
                        )
                    }

                    lifecycleScope.launch {
                        player.currentMediaItem?.mediaId?.let {
                            mediaRepository.onAudioPlayed(
                                it.toUri(),
                                player.currentPosition,
                            )
                        }
                    }
                }

                // Update the now playing widget
                if (events.containsAny(
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    )
                ) {
                    lifecycleScope.launch {
                        NowPlayingAppWidgetProvider.update(this@PlaybackService)
                    }
                }

                // Update the shuffle and repeat buttons
                if (events.containsAny(
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                    )
                ) {
                    mediaLibrarySession.setCustomLayout(getCustomLayout())
                }

                if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
                    openAudioEffectSession()
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = when (intent?.action) {
        ACTION_TOGGLE_PLAY_PAUSE -> {
            lifecycleScope.launch {
                when (player.playWhenReady) {
                    true -> player.pause()
                    false -> {
                        maybeLoadResumptionPlaylist()
                        player.play()
                    }
                }
            }

            START_STICKY
        }

        else -> super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (sharedPreferences.stopPlaybackOnTaskRemoved || !isPlaybackOngoing) {
            lifecycleScope.launch {
                if (isPlaybackOngoing) {
                    resumptionPlaylistRepository.onPlaybackPositionChanged(
                        player.currentMediaItemIndex,
                        player.currentPosition
                    )
                }
                pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onDestroy() {
        closeAudioEffectSession()

        player.removeAnalyticsListener(analyticsListener)

        player.release()
        mediaLibrarySession.release()

        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession

    private fun openAudioEffectSession() {
        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, application.packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            sendBroadcast(this)
        }
    }

    private fun closeAudioEffectSession() {
        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, application.packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            sendBroadcast(this)
        }
    }

    private fun getSingleTopActivity() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun getCustomLayout() = CustomCommand.entries.mapNotNull {
        it.buildCommandButton(player, resources)
    }

    /**
     * Get the resumption playlist as [MediaSession.MediaItemsWithStartPosition].
     * Returns an empty list if no valid media items are found.
     */
    private suspend fun getResumptionPlaylist(): MediaSession.MediaItemsWithStartPosition {
        val resumptionPlaylist = resumptionPlaylistRepository.getResumptionPlaylist()

        var startIndex = resumptionPlaylist.startIndex
        var startPositionMs = resumptionPlaylist.startPositionMs

        val mediaItems = resumptionPlaylist.mediaItemIds.mapAsync { itemId ->
            mediaRepositoryTree.getItem(itemId)
        }.withIndex().mapNotNull { (index, mediaItem) ->
            when (mediaItem) {
                null -> {
                    if (index == resumptionPlaylist.startIndex) {
                        // The playback position is now invalid
                        startPositionMs = 0

                        // Let's try the next item, this is done automatically since
                        // the next item will take this item's index
                    } else if (index < resumptionPlaylist.startIndex) {
                        // The missing media is before the start index, we have to offset
                        // the start by 1 entry
                        startIndex -= 1
                    }

                    null
                }

                else -> mediaItem
            }
        }

        return if (mediaItems.isEmpty()) {
            // No valid media items found, clear the resumption playlist
            resumptionPlaylistRepository.clearResumptionPlaylist()

            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        } else {
            MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                startIndex,
                startPositionMs
            )
        }
    }

    /**
     * If no media item is available, load the resumption playlist and seek to the last index and
     * position.
     */
    private suspend fun maybeLoadResumptionPlaylist() {
        if (player.mediaItemCount != 0) {
            return
        }

        val resumptionPlaylist = withContext(Dispatchers.IO) {
            getResumptionPlaylist()
        }
        if (resumptionPlaylist.mediaItems.isEmpty()) {
            Log.e(LOG_TAG, "No resumption playlist items found")
            return
        }

        player.setMediaItems(
            resumptionPlaylist.mediaItems,
            resumptionPlaylist.startIndex,
            resumptionPlaylist.startPositionMs
        )
        player.prepare()
    }

    private fun getOffloadMode(enabled: Boolean) = if (enabled) {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
    } else {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
    }

    companion object {
        private val LOG_TAG = PlaybackService::class.simpleName!!

        /**
         * Toggles play/pause. On play request and empty queue, resumption playlist will be loaded.
         */
        const val ACTION_TOGGLE_PLAY_PAUSE = "org.lineageos.twelve.ACTION_TOGGLE_PLAY_PAUSE"
    }
}
