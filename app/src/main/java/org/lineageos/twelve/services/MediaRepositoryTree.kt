/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.buildMediaItem
import org.lineageos.twelve.ext.permissionsGranted
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Provider
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.Result.Companion.getOrNull
import org.lineageos.twelve.repositories.MediaRepository
import org.lineageos.twelve.repositories.ProvidersRepository
import org.lineageos.twelve.utils.PermissionsUtils

class MediaRepositoryTree(
    private val context: Context,
    private val repository: MediaRepository,
    private val providersRepository: ProvidersRepository,
) {
    /**
     * No permissions media item.
     */
    private val noPermissionsMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_no_permissions),
        mediaId = NO_PERMISSIONS_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

    /**
     * No permissions description media item.
     */
    private val noPermissionsDescriptionMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_no_permissions_description),
        mediaId = NO_PERMISSIONS_DESCRIPTION_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = false,
        mediaType = MediaMetadata.MEDIA_TYPE_MIXED,
    )

    /**
     * Albums media item.
     */
    private val albumsMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_albums),
        mediaId = ALBUMS_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
    )

    /**
     * Artists media item.
     */
    private val artistsMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_artists),
        mediaId = ARTISTS_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
    )

    /**
     * Genres media item.
     */
    private val genresMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_genres),
        mediaId = GENRES_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_GENRES,
    )

    /**
     * Playlists media item.
     */
    private val playlistsMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_playlists),
        mediaId = PLAYLISTS_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
    )

    /**
     * Change provider media item.
     */
    private val changeProviderMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_change_provider),
        mediaId = CHANGE_PROVIDER_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

    /**
     * Provider changed media item.
     */
    private val providerChangedMediaItem = buildMediaItem(
        title = context.getString(R.string.library_item_provider_changed),
        mediaId = PROVIDER_CHANGED_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = false,
        mediaType = MediaMetadata.MEDIA_TYPE_MIXED,
    )

    /**
     * Get the root media item of the tree.
     */
    val rootMediaItem = buildMediaItem(
        title = context.getString(R.string.app_name),
        mediaId = ROOT_MEDIA_ITEM_ID,
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

    /**
     * Given a media ID, gets it's corresponding media item.
     */
    suspend fun getItem(mediaId: String) = when (mediaId) {
        ROOT_MEDIA_ITEM_ID -> rootMediaItem

        NO_PERMISSIONS_MEDIA_ITEM_ID -> noPermissionsMediaItem

        NO_PERMISSIONS_DESCRIPTION_MEDIA_ITEM_ID -> noPermissionsDescriptionMediaItem

        ALBUMS_MEDIA_ITEM_ID -> albumsMediaItem

        ARTISTS_MEDIA_ITEM_ID -> artistsMediaItem

        GENRES_MEDIA_ITEM_ID -> genresMediaItem

        PLAYLISTS_MEDIA_ITEM_ID -> playlistsMediaItem

        CHANGE_PROVIDER_MEDIA_ITEM_ID -> changeProviderMediaItem

        else -> when {
            mediaId.startsWith(PROVIDER_MEDIA_ITEM_ID_PREFIX) ->
                mediaIdToProvider(mediaId)?.toMedia3MediaItem()

            else -> mediaIdToMediaItem(mediaId)?.toMedia3MediaItem(context.resources)
        }
    }

    /**
     * Given an item's media ID, gets its children.
     */
    suspend fun getChildren(mediaId: String) = when (mediaId) {
        ROOT_MEDIA_ITEM_ID -> when (context.permissionsGranted(PermissionsUtils.mainPermissions)) {
            true -> listOf(
                albumsMediaItem,
                artistsMediaItem,
                genresMediaItem,
                playlistsMediaItem,
                changeProviderMediaItem,
            )

            false -> listOf(noPermissionsMediaItem)
        }

        NO_PERMISSIONS_MEDIA_ITEM_ID -> listOf(noPermissionsDescriptionMediaItem)

        NO_PERMISSIONS_DESCRIPTION_MEDIA_ITEM_ID -> listOf()

        ALBUMS_MEDIA_ITEM_ID -> repository.albums().toOneShotResult().map {
            it.toMedia3MediaItem(context.resources)
        }

        ARTISTS_MEDIA_ITEM_ID -> repository.artists().toOneShotResult().map {
            it.toMedia3MediaItem(context.resources)
        }

        GENRES_MEDIA_ITEM_ID -> repository.genres().toOneShotResult().map {
            it.toMedia3MediaItem(context.resources)
        }

        PLAYLISTS_MEDIA_ITEM_ID -> repository.playlists().toOneShotResult().map {
            it.toMedia3MediaItem(context.resources)
        }

        CHANGE_PROVIDER_MEDIA_ITEM_ID -> providersRepository.allProviders.first().map {
            it.toMedia3MediaItem()
        }

        else -> when {
            mediaId.startsWith(PROVIDER_MEDIA_ITEM_ID_PREFIX) -> {
                mediaIdToProvider(mediaId)?.let { provider ->
                    repository.setNavigationProvider(provider.identifier)
                }

                listOf(providerChangedMediaItem)
            }

            else -> mediaIdToMediaItemUri(mediaId)?.let { mediaItemUri ->
                when (mediaItemUriToMediaType(mediaItemUri)) {
                    MediaType.ALBUM -> repository.album(
                        mediaItemUri
                    ).toOneShotResult().second.map { albumAudios ->
                        albumAudios.toMedia3MediaItem(context.resources)
                    }

                    MediaType.ARTIST -> repository.artist(
                        mediaItemUri
                    ).toOneShotResult().second.let { artistWorks ->
                        listOf(
                            artistWorks.albums,
                            artistWorks.appearsInAlbum,
                        ).flatten().map { allRelatedAlbums ->
                            allRelatedAlbums.toMedia3MediaItem(context.resources)
                        }
                    }

                    MediaType.AUDIO -> listOf()

                    MediaType.GENRE -> repository.genre(
                        mediaItemUri
                    ).toOneShotResult().second.let { genreContent ->
                        listOf(
                            genreContent.appearsInAlbums,
                            genreContent.appearsInPlaylists,
                            genreContent.audios,
                        ).flatten().map { allRelatedMediaItems ->
                            allRelatedMediaItems.toMedia3MediaItem(context.resources)
                        }
                    }

                    MediaType.PLAYLIST -> repository.playlist(
                        mediaItemUri
                    ).toOneShotResult().second.map { playlistAudio ->
                        playlistAudio.toMedia3MediaItem(context.resources)
                    }

                    null -> null
                }
            }
        }
    } ?: emptyList()

    /**
     * Given a list of media items, gets an equivalent list of items that can be passed to the
     * player. This should be used with onAddMediaItems and onSetMediaItems.
     */
    suspend fun resolveMediaItems(mediaItems: List<MediaItem>) = buildList {
        mediaItems.forEach { item ->
            val searchQuery = item.requestMetadata.searchQuery
            when {
                searchQuery != null -> addAll(search(searchQuery))
                item.localConfiguration?.uri != null -> add(item)
                else -> getItem(item.mediaId)?.let { mediaItem ->
                    add(mediaItem)
                }
            }
        }
    }

    /**
     * Given a query, search for media items.
     */
    suspend fun search(query: String) = repository.search(query).toOneShotResult().map {
        it.toMedia3MediaItem(context.resources)
    }

    /**
     * Set the favorite status of a media item. Note that it will only work for audio items.
     *
     * @param mediaId The media ID of the item
     * @param isFavorite The new favorite status
     * @return Whether the operation was successful
     */
    suspend fun setFavorite(
        mediaId: String,
        isFavorite: Boolean,
    ) = mediaIdToMediaItemUri(mediaId)?.let {
        when (mediaItemUriToMediaType(it)) {
            MediaType.AUDIO -> repository.setFavorite(it, isFavorite).getOrNull()
            else -> null
        }
    }?.let { true } ?: false

    /**
     * Convert this media ID to a [Uri] if valid.
     */
    private fun mediaIdToMediaItemUri(mediaId: String) = runCatching {
        mediaId.toUri()
    }.getOrNull()

    private suspend fun mediaItemUriToMediaType(
        mediaItemUri: Uri
    ) = repository.mediaTypeOf(mediaItemUri)

    /**
     * Given a media ID, get the item from the repository.
     */
    private suspend fun mediaIdToMediaItem(
        mediaId: String
    ) = mediaIdToMediaItemUri(mediaId)?.let { uri ->
        when (mediaItemUriToMediaType(uri)) {
            MediaType.ALBUM -> repository.album(uri).toOneShotResult().first
            MediaType.ARTIST -> repository.artist(uri).toOneShotResult().first
            MediaType.AUDIO -> repository.audio(uri).toOneShotResult()
            MediaType.GENRE -> repository.genre(uri).toOneShotResult().first
            MediaType.PLAYLIST -> repository.playlist(uri).toOneShotResult().first
            null -> null
        }
    }

    private fun Provider.toMedia3MediaItem() = buildMediaItem(
        title = name,
        mediaId = "$PROVIDER_MEDIA_ITEM_ID_PREFIX${type.name};$typeId",
        isPlayable = false,
        isBrowsable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_MIXED,
        subtitle = context.getString(type.nameStringResId),
    )

    private suspend fun mediaIdToProvider(mediaId: String): Provider? {
        if (!mediaId.startsWith(PROVIDER_MEDIA_ITEM_ID_PREFIX)) {
            return null
        }

        val (type, typeId) = mediaId.removePrefix(PROVIDER_MEDIA_ITEM_ID_PREFIX).split(";")
        val providerType = ProviderType.valueOf(type)
        val providerTypeId = typeId.toLong()

        return providersRepository.provider(
            ProviderIdentifier(providerType, providerTypeId)
        ).first()
    }

    companion object {
        private val LOG_TAG = MediaRepositoryTree::class.simpleName!!

        // Root ID
        private const val ROOT_MEDIA_ITEM_ID = "[root]"

        // No permissions ID
        private const val NO_PERMISSIONS_MEDIA_ITEM_ID = "[no_permissions]"
        private const val NO_PERMISSIONS_DESCRIPTION_MEDIA_ITEM_ID = "[no_permissions_description]"

        // Root elements IDs
        private const val ALBUMS_MEDIA_ITEM_ID = "[albums]"
        private const val ARTISTS_MEDIA_ITEM_ID = "[artists]"
        private const val GENRES_MEDIA_ITEM_ID = "[genres]"
        private const val PLAYLISTS_MEDIA_ITEM_ID = "[playlists]"
        private const val CHANGE_PROVIDER_MEDIA_ITEM_ID = "[change_provider]"

        // Provider ID prefix
        private const val PROVIDER_MEDIA_ITEM_ID_PREFIX = "[provider]"

        // Provider changed ID
        private const val PROVIDER_CHANGED_MEDIA_ITEM_ID = "[provider_changed]"

        /**
         * Converts a flow of [Result] to a one-shot result of [T].
         * On success, returns the data.
         * On error, logs the exception and returns null.
         */
        private suspend fun <T, E> Flow<Result<T, E>>.toOneShotResult() =
            mapNotNull { status ->
                when (status) {
                    is Result.Success -> status.data
                    is Result.Failure -> {
                        Log.e(LOG_TAG, "Failed to get data", status.throwable)
                        null
                    }
                }
            }.first()
    }
}
