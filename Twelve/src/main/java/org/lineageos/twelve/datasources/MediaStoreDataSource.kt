/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import org.lineageos.twelve.R
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.mediastore.MediaStoreAudioUri
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.isRelativeTo
import org.lineageos.twelve.ext.mapEachRow
import org.lineageos.twelve.ext.queryFlow
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.ArtistWorks
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.DataSourceInformation
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.GenreContent
import org.lineageos.twelve.models.LocalizedString
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.ProviderArgument
import org.lineageos.twelve.models.ProviderArgument.Companion.requireArgument
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.Result.Companion.getOrNull
import org.lineageos.twelve.models.Result.Companion.map
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.SortingStrategy
import org.lineageos.twelve.models.Thumbnail
import org.lineageos.twelve.query.Query
import org.lineageos.twelve.query.and
import org.lineageos.twelve.query.eq
import org.lineageos.twelve.query.`in`
import org.lineageos.twelve.query.`is`
import org.lineageos.twelve.query.like
import org.lineageos.twelve.query.neq
import org.lineageos.twelve.query.query
import org.lineageos.twelve.repositories.ProvidersRepository

/**
 * [MediaStore.Audio] backed data source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreDataSource(
    private val contentResolver: ContentResolver,
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository,
    private val database: TwelveDatabase,
) : MediaDataSource {
    private inner class MediaStoreInstance(
        private val volumeName: String,
    ) : ProvidersManager.Instance {
        val albumsUri by lazy { getAlbumsUri(volumeName) }
        val artistsUri by lazy { getArtistsUri(volumeName) }
        val audiosUri by lazy { getAudiosUri(volumeName) }
        val genresUri by lazy { getGenresUri(volumeName) }

        override suspend fun isMediaItemCompatible(
            mediaItemUri: Uri
        ) = MediaStoreAudioUri.from(mediaItemUri)?.let {
            volumeName == it.volumeName
        } ?: listOf(
            playlistsBaseUri,
            favoritesUri,
        ).any { mediaItemUri.isRelativeTo(it) }

        fun mostPlayedAlbums(nTopTracks: Int = 100) =
            database.getLocalMediaStatsProviderDao()
                .getAllByPlayCount(nTopTracks)
                .mapLatest { stats -> stats.map { it.audioUri } }
                .flatMapLatest { uris ->
                    contentResolver.queryFlow(
                        audiosUri,
                        arrayOf(MediaStore.Audio.AlbumColumns.ALBUM_ID),
                        Bundle {
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SELECTION,
                                query {
                                    BaseColumns._ID `in` List(uris.size) { Query.ARG }
                                }
                            )
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                uris.map {
                                    ContentUris.parseId(it).toString()
                                }.toTypedArray()
                            )
                        }
                    )
                }
                .mapEachRow { it.getLong(MediaStore.Audio.AlbumColumns.ALBUM_ID) }
                .mapLatest { it.distinct() }
                .flatMapLatest { uris ->
                    contentResolver.queryFlow(
                        albumsUri,
                        albumsProjection,
                        Bundle {
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SELECTION,
                                query {
                                    MediaStore.Audio.AlbumColumns.ALBUM_ID `in` List(uris.size) {
                                        Query.ARG
                                    }
                                }
                            )
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                uris.map {
                                    it.toString()
                                }.toTypedArray()
                            )
                        }
                    ).mapEachRowToAlbum()
                }
                .mapLatest {
                    Result.Success(it)
                }

        fun Flow<Cursor?>.mapEachRowToAlbum() = mapEachRowToAlbum(volumeName)
        fun Flow<Cursor?>.mapEachRowToArtist() = mapEachRowToArtist(volumeName)
        fun Flow<Cursor?>.mapEachRowToAudio() = mapEachRowToAudio(volumeName)
        fun Flow<Cursor?>.mapEachRowToGenre() = mapEachRowToGenre(volumeName)
    }

    private val providersManager = ProvidersManager(
        coroutineScope,
        providersRepository,
        ProviderType.MEDIASTORE,
    ) { _, arguments ->
        val volumeName = arguments.requireArgument(ARG_VOLUME_NAME)

        MediaStoreInstance(volumeName)
    }

    override fun status(providerIdentifier: ProviderIdentifier) = flowOf(
        Result.Success(listOf<DataSourceInformation>())
    )

    override suspend fun mediaTypeOf(
        mediaItemUri: Uri,
    ) = MediaStoreAudioUri.from(mediaItemUri)?.let {
        when (it.type) {
            MediaStoreAudioUri.Type.ALBUMS -> MediaType.ALBUM
            MediaStoreAudioUri.Type.ARTISTS -> MediaType.ARTIST
            MediaStoreAudioUri.Type.GENRES -> MediaType.GENRE
            MediaStoreAudioUri.Type.MEDIA -> MediaType.AUDIO
        }
    } ?: when {
        mediaItemUri.isRelativeTo(playlistsBaseUri) -> MediaType.PLAYLIST
        mediaItemUri == favoritesUri -> MediaType.PLAYLIST
        else -> null
    }

    override fun providerOf(mediaItemUri: Uri) = providersManager.providerOf(mediaItemUri)

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        combine(
            mostPlayedAlbums(),
            albums(providerIdentifier, SortingRule(SortingStrategy.NAME)),
            artists(providerIdentifier, SortingRule(SortingStrategy.NAME)),
            genres(providerIdentifier, SortingRule(SortingStrategy.NAME)),
        ) { mostPlayed, albums, artists, genres ->
            Result.Success(
                listOf(
                    mostPlayed.map {
                        ActivityTab(
                            "most_played_albums",
                            LocalizedString.StringResIdLocalizedString(
                                R.string.activity_most_played_albums
                            ),
                            it,
                        )
                    },
                    albums.map {
                        ActivityTab(
                            "random_albums",
                            LocalizedString.StringResIdLocalizedString(
                                R.string.activity_random_albums
                            ),
                            it.shuffled(),
                        )
                    },
                    artists.map {
                        ActivityTab(
                            "random_artists",
                            LocalizedString.StringResIdLocalizedString(
                                R.string.activity_random_artists
                            ),
                            it.shuffled(),
                        )
                    },
                    genres.map {
                        ActivityTab(
                            "random_genres",
                            LocalizedString.StringResIdLocalizedString(
                                R.string.activity_random_genres
                            ),
                            it.shuffled(),
                        )
                    },
                ).mapNotNull {
                    it.getOrNull()?.takeIf { activityTab ->
                        activityTab.items.isNotEmpty()
                    }
                }
            )
        }
    }

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        contentResolver.queryFlow(
            albumsUri,
            albumsProjection,
            Bundle {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    listOfNotNull(
                        when (sortingRule.strategy) {
                            SortingStrategy.ARTIST_NAME -> MediaStore.Audio.AlbumColumns.ARTIST
                            SortingStrategy.CREATION_DATE -> MediaStore.Audio.AlbumColumns.LAST_YEAR
                            SortingStrategy.NAME -> MediaStore.Audio.AlbumColumns.ALBUM
                            else -> null
                        }?.let { column ->
                            when (sortingRule.reverse) {
                                true -> "$column DESC"
                                false -> column
                            }
                        },
                        MediaStore.Audio.AlbumColumns.ALBUM.takeIf {
                            sortingRule.strategy != SortingStrategy.NAME
                        },
                    ).toTypedArray()
                )
            }
        ).mapEachRowToAlbum().mapLatest {
            Result.Success(it)
        }
    }

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        contentResolver.queryFlow(
            artistsUri,
            artistsProjection,
            Bundle {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    listOfNotNull(
                        when (sortingRule.strategy) {
                            SortingStrategy.NAME -> MediaStore.Audio.ArtistColumns.ARTIST
                            else -> null
                        }?.let { column ->
                            when (sortingRule.reverse) {
                                true -> "$column DESC"
                                false -> column
                            }
                        },
                        MediaStore.Audio.ArtistColumns.ARTIST.takeIf {
                            sortingRule.strategy != SortingStrategy.NAME
                        },
                    ).toTypedArray()
                )
            }
        ).mapEachRowToArtist().mapLatest {
            Result.Success(it)
        }
    }

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        contentResolver.queryFlow(
            audiosUri,
            audiosProjection,
            Bundle {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    listOfNotNull(
                        when (sortingRule.strategy) {
                            SortingStrategy.ARTIST_NAME -> MediaStore.Audio.AudioColumns.ARTIST
                            SortingStrategy.CREATION_DATE -> MediaStore.Audio.AudioColumns.YEAR
                            SortingStrategy.NAME -> MediaStore.Audio.AudioColumns.TITLE
                            else -> null
                        }?.let { column ->
                            when (sortingRule.reverse) {
                                true -> "$column DESC"
                                false -> column
                            }
                        },
                        MediaStore.Audio.AudioColumns.TITLE.takeIf {
                            sortingRule.strategy != SortingStrategy.NAME
                        },
                    ).toTypedArray()
                )
            }
        ).mapEachRowToAudio().mapLatest {
            Result.Success(it)
        }
    }

    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        contentResolver.queryFlow(
            genresUri,
            genresProjection,
            Bundle {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    listOfNotNull(
                        when (sortingRule.strategy) {
                            SortingStrategy.NAME -> MediaStore.Audio.GenresColumns.NAME
                            else -> null
                        }?.let { column ->
                            when (sortingRule.reverse) {
                                true -> "$column DESC"
                                false -> column
                            }
                        },
                        MediaStore.Audio.GenresColumns.NAME.takeIf {
                            sortingRule.strategy != SortingStrategy.NAME
                        },
                    ).toTypedArray()
                )
            }
        ).mapEachRowToGenre().mapLatest {
            Result.Success(it)
        }
    }

    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = database.getPlaylistDao().getAll()
        .mapLatest { playlists ->
            Result.Success(
                buildList {
                    add(favoritesPlaylist)

                    playlists.forEach { add(it.toModel()) }
                }
            )
        }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        combine(
            contentResolver.queryFlow(
                albumsUri,
                albumsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.AlbumColumns.ALBUM like Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf("%${query}%")
                    )
                }
            ).mapEachRowToAlbum(),
            contentResolver.queryFlow(
                artistsUri,
                artistsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.ArtistColumns.ARTIST like Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(query)
                    )
                }
            ).mapEachRowToArtist(),
            contentResolver.queryFlow(
                audiosUri,
                audiosProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.AudioColumns.TITLE like Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(query)
                    )
                }
            ).mapEachRowToAudio(),
            contentResolver.queryFlow(
                genresUri,
                genresProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.GenresColumns.NAME like Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(query)
                    )
                }
            ).mapEachRowToGenre(),
        ) { albums, artists, audios, genres ->
            albums + artists + audios + genres
        }.mapLatest { Result.Success(it) }
    }

    override fun audio(
        audioUri: Uri
    ) = withVolumeName(audioUri) { volumeName ->
        contentResolver.queryFlow(
            getAudiosUri(volumeName),
            audiosProjection,
            Bundle {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    query {
                        BaseColumns._ID eq Query.ARG
                    }
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        ContentUris.parseId(audioUri).toString(),
                    )
                )
            }
        ).mapEachRowToAudio(volumeName).mapLatest { audios ->
            audios.firstOrNull()?.let {
                Result.Success(it)
            } ?: Result.Failure(Error.NOT_FOUND)
        }
    }

    override fun album(albumUri: Uri) = withVolumeName(albumUri) { volumeName ->
        combine(
            contentResolver.queryFlow(
                getAlbumsUri(volumeName),
                albumsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            BaseColumns._ID eq Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            ContentUris.parseId(albumUri).toString(),
                        )
                    )
                }
            ).mapEachRowToAlbum(volumeName),
            contentResolver.queryFlow(
                getAudiosUri(volumeName),
                audiosProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.AudioColumns.ALBUM_ID eq Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            ContentUris.parseId(albumUri).toString(),
                        )
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(
                            MediaStore.Audio.AudioColumns.TRACK,
                        )
                    )
                }
            ).mapEachRowToAudio(volumeName)
        ) { albums, audios ->
            albums.firstOrNull()?.let { album ->
                Result.Success(album to audios)
            } ?: Result.Failure(Error.NOT_FOUND)
        }
    }

    override fun artist(artistUri: Uri) = withVolumeName(artistUri) { volumeName ->
        combine(
            contentResolver.queryFlow(
                getArtistsUri(volumeName),
                artistsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            BaseColumns._ID eq Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            ContentUris.parseId(artistUri).toString(),
                        )
                    )
                }
            ).mapEachRowToArtist(volumeName),
            contentResolver.queryFlow(
                getAlbumsUri(volumeName),
                albumsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.AlbumColumns.ARTIST_ID eq Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            ContentUris.parseId(artistUri).toString(),
                        )
                    )
                }
            ).mapEachRowToAlbum(volumeName),
            contentResolver.queryFlow(
                getAudiosUri(volumeName),
                audioAlbumIdsProjection,
                Bundle {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        query {
                            MediaStore.Audio.AudioColumns.ARTIST_ID eq Query.ARG
                        }
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            ContentUris.parseId(artistUri).toString(),
                        )
                    )
                    putString(
                        ContentResolver.QUERY_ARG_SQL_GROUP_BY,
                        MediaStore.Audio.AudioColumns.ALBUM_ID
                    )
                }
            ).mapEachRow {
                it.getLong(MediaStore.Audio.AudioColumns.ALBUM_ID)
            }.flatMapLatest { albumIds ->
                contentResolver.queryFlow(
                    getAlbumsUri(volumeName),
                    albumsProjection,
                    Bundle {
                        putString(
                            ContentResolver.QUERY_ARG_SQL_SELECTION,
                            query {
                                (MediaStore.Audio.AudioColumns.ARTIST_ID neq Query.ARG) and
                                        (BaseColumns._ID `in` List(albumIds.size) { Query.ARG })
                            }
                        )
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(
                                ContentUris.parseId(artistUri).toString(),
                                *albumIds
                                    .map { it.toString() }
                                    .toTypedArray(),
                            )
                        )
                    }
                ).mapEachRowToAlbum(volumeName)
            }
        ) { artists, albums, appearsInAlbum ->
            artists.firstOrNull()?.let { artist ->
                val artistWorks = ArtistWorks(
                    albums,
                    appearsInAlbum,
                    listOf(),
                )

                Result.Success(artist to artistWorks)
            } ?: Result.Failure(Error.NOT_FOUND)
        }
    }

    override fun genre(genreUri: Uri) = withVolumeName(genreUri) { volumeName ->
        ContentUris.parseId(genreUri).let { genreId ->
            val (genreSelection, genreSelectionArgs) = when (genreId) {
                0L -> (MediaStore.Audio.AudioColumns.GENRE_ID `is` Query.NULL) to arrayOf()

                else -> (MediaStore.Audio.AudioColumns.GENRE_ID eq Query.ARG) to
                        arrayOf(genreId.toString())
            }

            combine(
                contentResolver.queryFlow(
                    getGenresUri(volumeName),
                    genresProjection,
                    Bundle {
                        putString(
                            ContentResolver.QUERY_ARG_SQL_SELECTION,
                            query {
                                when (genreId) {
                                    0L -> BaseColumns._ID `is` Query.NULL
                                    else -> BaseColumns._ID eq Query.ARG
                                }
                            }
                        )
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(
                                *when (genreId) {
                                    0L -> arrayOf()
                                    else -> arrayOf(genreId.toString())
                                }
                            )
                        )
                    }
                ).mapEachRowToGenre(volumeName),
                contentResolver.queryFlow(
                    getAudiosUri(volumeName),
                    audioAlbumIdsProjection,
                    Bundle {
                        putString(
                            ContentResolver.QUERY_ARG_SQL_SELECTION,
                            query {
                                genreSelection
                            }
                        )
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(
                                *genreSelectionArgs,
                            )
                        )
                        putString(
                            ContentResolver.QUERY_ARG_SQL_GROUP_BY,
                            MediaStore.Audio.AudioColumns.ALBUM_ID
                        )
                    }
                ).mapEachRow {
                    it.getLong(MediaStore.Audio.AudioColumns.ALBUM_ID)
                }.flatMapLatest { albumIds ->
                    contentResolver.queryFlow(
                        getAlbumsUri(volumeName),
                        albumsProjection,
                        Bundle {
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SELECTION,
                                query {
                                    BaseColumns._ID `in` List(albumIds.size) { Query.ARG }
                                }
                            )
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                arrayOf(
                                    *albumIds
                                        .map { it.toString() }
                                        .toTypedArray(),
                                )
                            )
                        }
                    ).mapEachRowToAlbum(volumeName)
                },
                contentResolver.queryFlow(
                    getAudiosUri(volumeName),
                    audiosProjection,
                    Bundle {
                        putString(
                            ContentResolver.QUERY_ARG_SQL_SELECTION,
                            query {
                                genreSelection
                            }
                        )
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(
                                *genreSelectionArgs,
                            )
                        )
                    }
                ).mapEachRowToAudio(volumeName)
            ) { genres, appearsInAlbums, audios ->
                val genre = genres.firstOrNull() ?: when (genreId) {
                    0L -> Genre.Builder(genreUri).build()
                    else -> null
                }

                genre?.let {
                    val genreContent = GenreContent(
                        appearsInAlbums,
                        listOf(),
                        audios,
                    )

                    Result.Success(it to genreContent)
                } ?: Result.Failure(Error.NOT_FOUND)
            }
        }
    }

    override fun playlist(playlistUri: Uri) = when {
        playlistUri == favoritesUri -> database.getFavoriteDao().getAll().flatMapLatest {
            audios(it).mapLatest { items ->
                Result.Success(favoritesPlaylist to items.filterNotNull())
            }
        }

        else -> database.getPlaylistDao().getPlaylistWithItems(
            ContentUris.parseId(playlistUri)
        ).flatMapLatest { data ->
            data?.let { playlistWithItems ->
                val playlist = playlistWithItems.playlist.toModel()

                audios(playlistWithItems.items).mapLatest { items ->
                    Result.Success(playlist to items.filterNotNull())
                }
            } ?: flowOf(Result.Failure(Error.NOT_FOUND))
        }
    }

    override fun audioPlaylistsStatus(audioUri: Uri) = combine(
        database.getFavoriteDao().containsFlow(audioUri),
        database.getPlaylistWithItemsDao().getPlaylistsWithItemStatus(audioUri),
    ) { isFavorite, playlistsWithItemStatus ->
        Result.Success(
            buildList {
                add(favoritesPlaylist to isFavorite)

                playlistsWithItemStatus.forEach {
                    add(it.playlist.toModel() to it.value)
                }
            }
        )
    }

    override fun lyrics(audioUri: Uri) = flowOf(
        Result.Failure(Error.NOT_IMPLEMENTED)
    )

    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ) = database.getPlaylistDao().create(
        name
    ).let {
        Result.Success(ContentUris.withAppendedId(playlistsBaseUri, it))
    }

    override suspend fun renamePlaylist(playlistUri: Uri, name: String) = when {
        playlistUri == favoritesUri -> Result.Failure(Error.IO)
        else -> database.getPlaylistDao().rename(ContentUris.parseId(playlistUri), name).let {
            Result.Success(Unit)
        }
    }

    override suspend fun deletePlaylist(playlistUri: Uri) = when {
        playlistUri == favoritesUri -> Result.Failure(Error.IO)
        else -> database.getPlaylistDao().delete(ContentUris.parseId(playlistUri)).let {
            Result.Success(Unit)
        }
    }

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ) = when {
        playlistUri == favoritesUri -> setFavorite(audioUri, true)
        else -> database.getPlaylistWithItemsDao().addItemToPlaylist(
            ContentUris.parseId(playlistUri),
            audioUri
        ).let {
            Result.Success(Unit)
        }
    }

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ) = when {
        playlistUri == favoritesUri -> setFavorite(audioUri, false)
        else -> database.getPlaylistWithItemsDao().removeItemFromPlaylist(
            ContentUris.parseId(playlistUri),
            audioUri
        ).let {
            Result.Success(Unit)
        }
    }

    override suspend fun onAudioPlayed(
        audioUri: Uri,
        positionMs: Long,
    ): MediaRequestStatus<Unit> {
        database.getLocalMediaStatsProviderDao().increasePlayCount(audioUri)
        return Result.Success(Unit)
    }

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean
    ) = when (isFavorite) {
        true -> database.getFavoriteDao().add(audioUri)
        false -> database.getFavoriteDao().remove(audioUri)
    }.let {
        Result.Success(Unit)
    }

    fun audios() = contentResolver.queryFlow(
        getAudiosUri(MediaStore.VOLUME_EXTERNAL),
        audiosProjection
    ).mapEachRowToAudio(MediaStore.VOLUME_EXTERNAL)

    /**
     * Given a list of audio URIs, return a list of [Audio], where null if the audio hasn't been
     * found.
     */
    fun audios(audioUris: List<Uri>) = audioUris.map {
        ContentUris.parseId(it).toString()
    }.let { audioIds ->
        contentResolver.queryFlow(
            getAudiosUri(MediaStore.VOLUME_EXTERNAL),
            audiosProjection,
            Bundle {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    query {
                        BaseColumns._ID `in` List(audioIds.size) { Query.ARG }
                    }
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    audioIds.toTypedArray()
                )
            }
        )
            .mapEachRowToAudio(MediaStore.VOLUME_EXTERNAL)
            .mapLatest { audios ->
                audioUris.map { audioUri ->
                    audios.firstOrNull {
                        it.uri.lastPathSegment == audioUri.lastPathSegment
                    }?.copy(uri = audioUri)
                }
            }
    }

    private fun <T> withVolumeName(
        mediaItemUri: Uri,
        block: (volumeName: String) -> Flow<Result<T, Error>>,
    ) = MediaStoreAudioUri.from(mediaItemUri)?.let {
        block(it.volumeName)
    } ?: flowOf(Result.Failure(Error.NOT_FOUND))

    private fun getAlbumsUri(
        volumeName: String
    ): Uri = MediaStore.Audio.Albums.getContentUri(volumeName)

    private fun getArtistsUri(
        volumeName: String
    ): Uri = MediaStore.Audio.Artists.getContentUri(volumeName)

    private fun getAudiosUri(
        volumeName: String
    ): Uri = MediaStore.Audio.Media.getContentUri(volumeName)

    private fun getGenresUri(
        volumeName: String
    ): Uri = MediaStore.Audio.Genres.getContentUri(volumeName)

    private fun getAlbumsArtUri(
        volumeName: String
    ): Uri = MediaStore.AUTHORITY_URI.buildUpon()
        .appendPath(volumeName)
        .appendPath("audio")
        .appendPath(AUDIO_ALBUMART)
        .build()

    private fun Flow<Cursor?>.mapEachRowToAlbum(volumeName: String) = run {
        val albumsUri = getAlbumsUri(volumeName)
        val artistsUri = getArtistsUri(volumeName)
        val albumsArtUri = getAlbumsArtUri(volumeName)

        mapEachRow { columnIndexCache ->
            val albumId = columnIndexCache.getLong(BaseColumns._ID)
            val album = columnIndexCache.getStringOrNull(MediaStore.Audio.AlbumColumns.ALBUM)
            val artistId = columnIndexCache.getLong(MediaStore.Audio.AlbumColumns.ARTIST_ID)
            val artist = columnIndexCache.getStringOrNull(MediaStore.Audio.AlbumColumns.ARTIST)
            val lastYear = columnIndexCache.getInt(MediaStore.Audio.AlbumColumns.LAST_YEAR)

            val uri = ContentUris.withAppendedId(albumsUri, albumId)
            val artistUri = ContentUris.withAppendedId(artistsUri, artistId)

            val albumArtUri = ContentUris.withAppendedId(albumsArtUri, albumId)

            val thumbnail = Thumbnail.Builder()
                .setUri(albumArtUri)
                .setType(Thumbnail.Type.FRONT_COVER)
                .build()

            Album.Builder(uri)
                .setThumbnail(thumbnail)
                .setTitle(album?.takeIf { it != MediaStore.UNKNOWN_STRING })
                .setArtistUri(artistUri)
                .setArtistName(artist?.takeIf { it != MediaStore.UNKNOWN_STRING })
                .setYear(lastYear.takeIf { it != 0 })
                .build()
        }
    }

    private fun Flow<Cursor?>.mapEachRowToArtist(volumeName: String) = run {
        val artistsUri = MediaStore.Audio.Artists.getContentUri(volumeName)

        mapEachRow { columnIndexCache ->
            val artistId = columnIndexCache.getLong(BaseColumns._ID)
            val artist = columnIndexCache.getStringOrNull(MediaStore.Audio.ArtistColumns.ARTIST)

            val uri = ContentUris.withAppendedId(artistsUri, artistId)

            Artist.Builder(uri)
                .setName(artist?.takeIf { it != MediaStore.UNKNOWN_STRING })
                .build()
        }
    }

    private fun Flow<Cursor?>.mapEachRowToAudio(volumeName: String) = run {
        val audiosUri = MediaStore.Audio.Media.getContentUri(volumeName)
        val artistsUri = MediaStore.Audio.Artists.getContentUri(volumeName)
        val albumsUri = MediaStore.Audio.Albums.getContentUri(volumeName)
        val genresUri = MediaStore.Audio.Genres.getContentUri(volumeName)

        mapEachRow { columnIndexCache ->
            val audioId = columnIndexCache.getLong(BaseColumns._ID)
            val mimeType = columnIndexCache.getString(MediaStore.Audio.AudioColumns.MIME_TYPE)
            val title = columnIndexCache.getString(MediaStore.Audio.AudioColumns.TITLE)
            val isMusic = columnIndexCache.getBoolean(MediaStore.Audio.AudioColumns.IS_MUSIC)
            val isPodcast = columnIndexCache.getBoolean(MediaStore.Audio.AudioColumns.IS_PODCAST)
            val isAudiobook =
                columnIndexCache.getBoolean(MediaStore.Audio.AudioColumns.IS_AUDIOBOOK)
            val duration = columnIndexCache.getLong(MediaStore.Audio.AudioColumns.DURATION)
            val artistId = columnIndexCache.getLong(MediaStore.Audio.AudioColumns.ARTIST_ID)
            val artist = columnIndexCache.getStringOrNull(MediaStore.Audio.AudioColumns.ARTIST)
            val albumId = columnIndexCache.getLong(MediaStore.Audio.AudioColumns.ALBUM_ID)
            val album = columnIndexCache.getStringOrNull(MediaStore.Audio.AudioColumns.ALBUM)
            val track = columnIndexCache.getInt(MediaStore.Audio.AudioColumns.TRACK)
            val genreId = columnIndexCache.getLong(MediaStore.Audio.AudioColumns.GENRE_ID)
            val genre = columnIndexCache.getStringOrNull(MediaStore.Audio.AudioColumns.GENRE)
            val year = columnIndexCache.getInt(MediaStore.Audio.AudioColumns.YEAR)

            val isRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                columnIndexCache.getBoolean(MediaStore.Audio.AudioColumns.IS_RECORDING)
            } else {
                false
            }

            val uri = ContentUris.withAppendedId(audiosUri, audioId)
            val artistUri = ContentUris.withAppendedId(artistsUri, artistId)
            val albumUri = ContentUris.withAppendedId(albumsUri, albumId)
            val genreUri = ContentUris.withAppendedId(genresUri, genreId)

            val audioType = when {
                isMusic -> Audio.Type.MUSIC
                isPodcast -> Audio.Type.PODCAST
                isAudiobook -> Audio.Type.AUDIOBOOK
                isRecording -> Audio.Type.RECORDING
                else -> Audio.Type.MUSIC
            }

            val (discNumber, discTrack) = track.takeUnless { it == 0 }?.let {
                when (track > 1000) {
                    true -> track / 1000 to track % 1000
                    false -> null to track
                }
            } ?: (null to null)

            val albumArtUri = uri.buildUpon()
                .appendPath(AUDIO_ALBUMART)
                .build()

            val thumbnail = Thumbnail.Builder()
                .setUri(albumArtUri)
                .setType(Thumbnail.Type.FRONT_COVER)
                .build()

            Audio.Builder(uri)
                .setThumbnail(thumbnail)
                .setPlaybackUri(uri)
                .setMimeType(mimeType)
                .setTitle(title)
                .setType(audioType)
                .setDurationMs(duration)
                .setArtistUri(artistUri)
                .setArtistName(artist?.takeIf { it != MediaStore.UNKNOWN_STRING })
                .setAlbumUri(albumUri)
                .setAlbumTitle(album?.takeIf { it != MediaStore.UNKNOWN_STRING })
                .setDiscNumber(discNumber)
                .setTrackNumber(discTrack)
                .setGenreUri(genreUri)
                .setGenreName(genre)
                .setYear(year.takeIf { it != 0 })
                .build()
        }.flatMapLatest { audios ->
            when (audios.isNotEmpty()) {
                true -> combine(
                    audios.map { audio ->
                        database.getFavoriteDao().containsFlow(audio.uri)
                            .mapLatest { isFavorite ->
                                audio.copy(isFavorite = isFavorite)
                            }
                    }
                ) { it.toList() }

                false -> flowOf(listOf())
            }
        }
    }

    private fun Flow<Cursor?>.mapEachRowToGenre(volumeName: String) = run {
        val genresUri = MediaStore.Audio.Genres.getContentUri(volumeName)

        mapEachRow { columnIndexCache ->
            val genreId = columnIndexCache.getLong(BaseColumns._ID)
            val name = columnIndexCache.getStringOrNull(MediaStore.Audio.GenresColumns.NAME)

            val uri = ContentUris.withAppendedId(genresUri, genreId)

            Genre.Builder(uri)
                .setName(name)
                .build()
        }
    }

    companion object {
        // packages/providers/MediaProvider/src/com/android/providers/media/LocalUriMatcher.java
        private const val AUDIO_ALBUMART = "albumart"

        private val albumsProjection = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.AlbumColumns.ALBUM,
            MediaStore.Audio.AlbumColumns.ARTIST_ID,
            MediaStore.Audio.AlbumColumns.ARTIST,
            MediaStore.Audio.AlbumColumns.LAST_YEAR,
        )

        private val artistsProjection = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.ArtistColumns.ARTIST,
        )

        private val genresProjection = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.GenresColumns.NAME,
        )

        private val audiosProjection = mutableListOf(
            BaseColumns._ID,
            MediaStore.Audio.AudioColumns.MIME_TYPE,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.IS_MUSIC,
            MediaStore.Audio.AudioColumns.IS_PODCAST,
            MediaStore.Audio.AudioColumns.IS_AUDIOBOOK,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.AudioColumns.ARTIST_ID,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM_ID,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.Audio.AudioColumns.GENRE_ID,
            MediaStore.Audio.AudioColumns.GENRE,
            MediaStore.Audio.AudioColumns.YEAR,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(MediaStore.Audio.AudioColumns.IS_RECORDING)
            }
        }.toTypedArray()

        private val audioAlbumIdsProjection = arrayOf(
            MediaStore.Audio.AudioColumns.ALBUM_ID,
        )

        /**
         * Dummy internal database scheme.
         */
        private const val DATABASE_SCHEME = "twelve_database"

        /**
         * Dummy database playlists authority.
         */
        private const val PLAYLISTS_AUTHORITY = "playlists"

        /**
         * Dummy database favorites authority.
         */
        private const val FAVORITES_AUTHORITY = "favorites"

        /**
         * Dummy internal database playlists [Uri].
         */
        private val playlistsBaseUri = Uri.Builder()
            .scheme(DATABASE_SCHEME)
            .authority(PLAYLISTS_AUTHORITY)
            .build()

        private val favoritesUri = Uri.Builder()
            .scheme(DATABASE_SCHEME)
            .authority(FAVORITES_AUTHORITY)
            .build()

        private val favoritesPlaylist = Playlist.Builder(favoritesUri)
            .setType(Playlist.Type.FAVORITES)
            .build()

        private fun org.lineageos.twelve.database.entities.Playlist.toModel() =
            Playlist.Builder(ContentUris.withAppendedId(playlistsBaseUri, id))
                .setName(name)
                .build()

        val ARG_VOLUME_NAME = ProviderArgument(
            "volume_name",
            String::class,
            R.string.provider_argument_volume_name,
            required = true,
            hidden = false,
        )
    }
}
