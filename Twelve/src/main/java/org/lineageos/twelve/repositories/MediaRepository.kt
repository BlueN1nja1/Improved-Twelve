/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.repositories

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import okhttp3.Cache
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.AmpacheDataSource
import org.lineageos.twelve.datasources.FileDataSource
import org.lineageos.twelve.datasources.JellyfinDataSource
import org.lineageos.twelve.datasources.MediaDataSource
import org.lineageos.twelve.datasources.MediaStoreDataSource
import org.lineageos.twelve.datasources.SubsonicDataSource
import org.lineageos.twelve.ext.DEFAULT_PROVIDER_KEY
import org.lineageos.twelve.ext.defaultProvider
import org.lineageos.twelve.ext.preferenceFlow
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.SortingStrategy

/**
 * Media repository. This class coordinates all the providers and their data source.
 * All methods that involves a URI as a parameter will be redirected to the
 * proper data source that can handle the media item. Methods that just returns a list of things
 * will be redirected to the provider selected by the user (see [navigationProvider]).
 * If the navigation provider disappears, the local provider will be used as a fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepository(
    context: Context,
    scope: CoroutineScope,
    providersRepository: ProvidersRepository,
    private val database: TwelveDatabase,
) {
    /**
     * Shared preferences.
     */
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * HTTP cache
     * 50 MB should be enough for most cases.
     */
    private val cache = Cache(context.cacheDir, 50 * 1024 * 1024)

    /**
     * MediaStore data source.
     */
    private val mediaStoreDataSource = MediaStoreDataSource(
        context.contentResolver,
        scope,
        providersRepository,
        database,
    )

    /**
     * Subsonic data source.
     */
    private val subsonicDataSource = SubsonicDataSource(
        scope,
        providersRepository,
        cache,
    )

    /**
     * Jellyfin data source.
     */
    private val jellyfinDataSource = JellyfinDataSource(
        context,
        scope,
        providersRepository,
        database,
        "deviceIdentifier",
        cache,
    )

    /**
     * Ampache data source.
     */
    private val ampacheDataSource = AmpacheDataSource(
        scope,
        providersRepository,
        database,
        "deviceIdentifier",
        cache,
    )

    /**
     * File data source.
     */
    private val fileDataSource = FileDataSource(
        context.contentResolver,
        cache,
    )

    private val allDataSources = MutableStateFlow(
        listOf(
            mediaStoreDataSource,
            subsonicDataSource,
            jellyfinDataSource,
            ampacheDataSource,
            fileDataSource,
        )
    ).asStateFlow()

    /**
     * The current navigation provider's identifiers.
     */
    private val navigationProviderIdentifier = sharedPreferences.preferenceFlow(
        DEFAULT_PROVIDER_KEY, getter = SharedPreferences::defaultProvider
    )
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope,
            SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    /**
     * The current navigation provider.
     */
    val navigationProvider = combine(
        navigationProviderIdentifier,
        providersRepository.allProviders,
    ) { navigationProviderIdentifier, allProviders ->
        navigationProviderIdentifier?.let {
            allProviders.firstOrNull { provider ->
                provider.type == it.type && provider.typeId == it.typeId
            }
        } ?: allProviders.firstOrNull()
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    init {
        scope.launch { gcLocalMediaStats() }
    }

    /**
     * Change the default navigation provider. In case this provider disappears the repository will
     * automatically fallback to the local provider.
     *
     * @param providerIdentifier The new navigation provider identifier
     */
    fun setNavigationProvider(providerIdentifier: ProviderIdentifier) {
        sharedPreferences.defaultProvider = providerIdentifier
    }

    /**
     * Delete all local stats entries.
     */
    suspend fun resetLocalStats() {
        database.getLocalMediaStatsProviderDao().deleteAll()
    }

    /**
     * @see MediaDataSource.status
     */
    fun status(
        providerIdentifier: ProviderIdentifier
    ) = withProviderDataSource(providerIdentifier) {
        status(providerIdentifier)
    }

    /**
     * @see MediaDataSource.mediaTypeOf
     */
    suspend fun mediaTypeOf(
        mediaItemUri: Uri
    ) = allDataSources.value.firstNotNullOfOrNull { dataSource ->
        dataSource.mediaTypeOf(mediaItemUri)
    }

    /**
     * @see MediaDataSource.providerOf
     */
    fun providerOf(mediaItemUri: Uri) = withMediaItemsDataSourceFlow(mediaItemUri) {
        providerOf(mediaItemUri)
    }

    /**
     * @see MediaDataSource.activity
     */
    fun activity() = withNavigationDataSourceAndProviderFlow { activity(it) }

    /**
     * @see MediaDataSource.albums
     */
    fun albums(
        sortingRule: SortingRule = defaultAlbumsSortingRule,
    ) = withNavigationDataSourceAndProviderFlow {
        albums(it, sortingRule)
    }

    /**
     * @see MediaDataSource.artists
     */
    fun artists(
        sortingRule: SortingRule = defaultArtistsSortingRule,
    ) = withNavigationDataSourceAndProviderFlow { artists(it, sortingRule) }

    /**
     * @see MediaDataSource.audios
     */
    fun audios(
        sortingRule: SortingRule = defaultAudiosSortingRule,
    ) = withNavigationDataSourceAndProviderFlow { audios(it, sortingRule) }

    /**
     * @see MediaDataSource.genres
     */
    fun genres(
        sortingRule: SortingRule = defaultGenresSortingRule,
    ) = withNavigationDataSourceAndProviderFlow { genres(it, sortingRule) }

    /**
     * @see MediaDataSource.playlists
     */
    fun playlists(
        sortingRule: SortingRule = defaultPlaylistsSortingRule,
    ) = withNavigationDataSourceAndProviderFlow { playlists(it, sortingRule) }

    /**
     * @see MediaDataSource.search
     */
    fun search(query: String) = withNavigationDataSourceAndProviderFlow { search(it, query) }

    /**
     * @see MediaDataSource.audio
     */
    fun audio(audioUri: Uri) = withMediaItemsDataSourceFlow(audioUri) {
        audio(audioUri)
    }

    /**
     * @see MediaDataSource.album
     */
    fun album(albumUri: Uri) = withMediaItemsDataSourceFlow(albumUri) {
        album(albumUri)
    }

    /**
     * @see MediaDataSource.artist
     */
    fun artist(artistUri: Uri) = withMediaItemsDataSourceFlow(artistUri) {
        artist(artistUri)
    }

    /**
     * @see MediaDataSource.genre
     */
    fun genre(genreUri: Uri) = withMediaItemsDataSourceFlow(genreUri) {
        genre(genreUri)
    }

    /**
     * @see MediaDataSource.playlist
     */
    fun playlist(playlistUri: Uri) = withMediaItemsDataSourceFlow(playlistUri) {
        playlist(playlistUri)
    }

    /**
     * @see MediaDataSource.audioPlaylistsStatus
     */
    fun audioPlaylistsStatus(audioUri: Uri) = withMediaItemsDataSourceFlow(audioUri) {
        audioPlaylistsStatus(audioUri)
    }

    /**
     * @see MediaDataSource.lyrics
     */
    fun lyrics(audioUri: Uri) = withMediaItemsDataSourceFlow(audioUri) {
        lyrics(audioUri)
    }

    /**
     * @see MediaDataSource.createPlaylist
     */
    suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ) = getDataSource(providerIdentifier).createPlaylist(
        providerIdentifier,
        name,
    )

    /**
     * @see MediaDataSource.renamePlaylist
     */
    suspend fun renamePlaylist(playlistUri: Uri, name: String) =
        withMediaItemsDataSource(playlistUri) {
            renamePlaylist(playlistUri, name)
        }

    /**
     * @see MediaDataSource.deletePlaylist
     */
    suspend fun deletePlaylist(playlistUri: Uri) = withMediaItemsDataSource(playlistUri) {
        deletePlaylist(playlistUri)
    }

    /**
     * @see MediaDataSource.addAudioToPlaylist
     */
    suspend fun addAudioToPlaylist(playlistUri: Uri, audioUri: Uri) =
        withMediaItemsDataSource(playlistUri, audioUri) {
            addAudioToPlaylist(playlistUri, audioUri)
        }

    /**
     * @see MediaDataSource.removeAudioFromPlaylist
     */
    suspend fun removeAudioFromPlaylist(playlistUri: Uri, audioUri: Uri) =
        withMediaItemsDataSource(playlistUri, audioUri) {
            removeAudioFromPlaylist(playlistUri, audioUri)
        }

    /**
     * @see MediaDataSource.onAudioPlayed
     */
    suspend fun onAudioPlayed(audioUri: Uri, positionMs: Long) =
        withMediaItemsDataSource(audioUri) {
            onAudioPlayed(audioUri, positionMs)
        }

    /**
     * @see MediaDataSource.setFavorite
     */
    suspend fun setFavorite(audioUri: Uri, favorite: Boolean) =
        withMediaItemsDataSource(audioUri) {
            setFavorite(audioUri, favorite)
        }

    /**
     * Get the [MediaDataSource] associated with the given [ProviderIdentifier].
     *
     * @param providerIdentifier The [ProviderIdentifier]
     * @return The corresponding [MediaDataSource]
     */
    private fun getDataSource(
        providerIdentifier: ProviderIdentifier,
    ) = when (providerIdentifier.type) {
        ProviderType.MEDIASTORE -> mediaStoreDataSource
        ProviderType.SUBSONIC -> subsonicDataSource
        ProviderType.JELLYFIN -> jellyfinDataSource
        ProviderType.AMPACHE -> ampacheDataSource
    }

    /**
     * Find the [MediaDataSource] that matches the given [ProviderIdentifier] and call the given
     * predicate on it.
     *
     * @param providerIdentifier The [ProviderIdentifier]
     * @return A flow containing the result of the predicate. It will emit a not found error if
     *   no [MediaDataSource] matches the given provider
     */
    private fun <T> withProviderDataSource(
        providerIdentifier: ProviderIdentifier,
        predicate: MediaDataSource.() -> Flow<Result<T, Error>>
    ) = getDataSource(providerIdentifier).predicate()

    /**
     * Find the [MediaDataSource] that handles the given URIs and call the given predicate on it.
     *
     * @param uris The URIs to check
     * @param predicate The predicate to call on the [MediaDataSource]
     * @return A flow containing the result of the predicate. It will emit a not found error if
     *   no [MediaDataSource] can handle the given URIs
     */
    private fun <T> withMediaItemsDataSourceFlow(
        vararg uris: Uri, predicate: MediaDataSource.() -> Flow<Result<T, Error>>
    ) = allDataSources.flatMapLatest {
        it.firstOrNull { dataSource ->
            uris.all { uri -> dataSource.isMediaItemCompatible(uri) }
        }?.predicate() ?: flowOf(Result.Failure(Error.NOT_FOUND))
    }

    /**
     * Find the [MediaDataSource] that handles the given URIs and call the given predicate on it.
     *
     * @param uris The URIs to check
     * @param predicate The predicate to call on the [MediaDataSource]
     * @return A [Result] containing the result of the predicate. It will return a not found
     *   error if no [MediaDataSource] can handle the given URIs
     */
    private suspend fun <T> withMediaItemsDataSource(
        vararg uris: Uri, predicate: suspend MediaDataSource.() -> Result<T, Error>
    ) = allDataSources.value.firstOrNull { dataSource ->
        uris.all { uri -> dataSource.isMediaItemCompatible(uri) }
    }?.predicate() ?: Result.Failure(Error.NOT_FOUND)

    private fun <T> withNavigationDataSourceAndProviderFlow(
        predicate: MediaDataSource.(ProviderIdentifier) -> Flow<Result<T, Error>>
    ) = navigationProvider.flatMapLatest {
        it?.let {
            withProviderDataSource(it.identifier) {
                predicate(it.identifier)
            }
        } ?: flowOf(Result.Failure(Error.NOT_FOUND))
    }

    private suspend fun MediaDataSource.isMediaItemCompatible(
        mediaItemUri: Uri
    ) = mediaTypeOf(mediaItemUri) != null

    companion object {
        val defaultAlbumsSortingRule = SortingRule(
            SortingStrategy.CREATION_DATE, true
        )

        val defaultArtistsSortingRule = SortingRule(
            SortingStrategy.MODIFICATION_DATE, true
        )

        val defaultAudiosSortingRule = SortingRule(
            SortingStrategy.NAME
        )

        val defaultGenresSortingRule = SortingRule(
            SortingStrategy.NAME
        )

        val defaultPlaylistsSortingRule = SortingRule(
            SortingStrategy.MODIFICATION_DATE, true
        )
    }

    /**
     * Remove items that are no longer in the local data source from the local media stats table.
     */
    private suspend fun gcLocalMediaStats() {
        val statsDao = database.getLocalMediaStatsProviderDao()
        val allStats = statsDao.getAll()
        val inSource = mediaStoreDataSource.mediaStatsUris().first()

        val removedMedia = allStats.mapNotNull {
            val notPresent = it.uri !in inSource

            when (notPresent) {
                true -> it.uri
                false -> null
            }
        }

        if (removedMedia.isNotEmpty()) {
            statsDao.delete(removedMedia)
        }
    }
}
