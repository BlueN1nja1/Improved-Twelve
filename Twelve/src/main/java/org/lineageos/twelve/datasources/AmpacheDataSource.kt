/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.lineageos.twelve.R
import org.lineageos.twelve.database.TwelveDatabase
import org.lineageos.twelve.datasources.ampache.AmpacheClient
import org.lineageos.twelve.ext.isRelativeTo
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
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.ProviderArgument
import org.lineageos.twelve.models.ProviderArgument.Companion.requireArgument
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.Result.Companion.flatMap
import org.lineageos.twelve.models.Result.Companion.getOrNull
import org.lineageos.twelve.models.Result.Companion.map
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.SortingStrategy
import org.lineageos.twelve.models.Thumbnail
import org.lineageos.twelve.repositories.ProvidersRepository
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AmpacheDataSource(
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository,
    database: TwelveDatabase,
    deviceIdentifier: String,
    cache: Cache? = null,
) : MediaDataSource {
    private class AmpacheInstance(
        val client: AmpacheClient,
        server: String,
    ) : ProvidersManager.Instance {
        private val dataSourceBaseUri = server.toUri()

        val albumsUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(ALBUMS_PATH)
            .build()
        val artistsUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(ARTISTS_PATH)
            .build()
        val audiosUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(AUDIOS_PATH)
            .build()
        val genresUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(GENRES_PATH)
            .build()
        val playlistsUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(PLAYLISTS_PATH)
            .build()

        val favoritesUri: Uri = dataSourceBaseUri.buildUpon()
            .appendPath(FAVORITES_PATH)
            .build()
        val favoritesPlaylist = Playlist.Builder(favoritesUri)
            .setType(Playlist.Type.FAVORITES)
            .build()

        /**
         * This flow is used to signal a change in the playlists.
         */
        val playlistsChanged = MutableStateFlow(Any())

        /**
         * This flow is used to signal a change in the favorites.
         */
        val favoritesChanged = MutableStateFlow(Any())

        override suspend fun isMediaItemCompatible(
            mediaItemUri: Uri
        ) = mediaItemUri.isRelativeTo(dataSourceBaseUri)

        fun org.lineageos.twelve.datasources.ampache.models.Album.toMediaItem() =
            Album.Builder(getAlbumUri(id))
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(art?.toUri())
                        .build()
                )
                .setTitle(name)
                .setArtistUri(artist?.id?.let(::getArtistUri))
                .setArtistName(artist?.name)
                .setYear(year)
                .build()

        fun org.lineageos.twelve.datasources.ampache.models.Artist.toMediaItem() =
            Artist.Builder(getArtistUri(id))
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(art?.toUri())
                        .build()
                )
                .setName(name)
                .build()

        fun org.lineageos.twelve.datasources.ampache.models.Genre.toMediaItem() =
            Genre.Builder(getGenreUri(id))
                .setName(name)
                .build()

        fun org.lineageos.twelve.datasources.ampache.models.Lyrics.toModel() =
            Lyrics.Builder()
                .apply {
                    text.lines().forEach { line ->
                        addLine(line)
                    }
                }
                .build()

        fun org.lineageos.twelve.datasources.ampache.models.Playlist.toMediaItem() =
            Playlist.Builder(getPlaylistUri(id))
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(art.toUri())
                        .build()
                )
                .setName(name)
                .setType(Playlist.Type.PLAYLIST)
                .build()

        fun org.lineageos.twelve.datasources.ampache.models.Song.toMediaItem() =
            Audio.Builder(getAudioUri(id))
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(art.toUri())
                        .build()
                )
                .setPlaybackUri(url.toUri())
                .setMimeType(streamMime)
                .setTitle(name)
                .setType(Audio.Type.MUSIC)
                .setDurationMs(time.times(1000L))
                .setArtistUri(getArtistUri(artist.id))
                .setArtistName(artist.name)
                .setAlbumUri(getAlbumUri(album.id))
                .setAlbumTitle(album.name)
                .setDiscNumber(disk)
                .setTrackNumber(track)
                .setGenreUri(genre.firstOrNull()?.let { getGenreUri(it.id) })
                .setGenreName(genre.firstOrNull()?.name)
                .setYear(year)
                .setIsFavorite(flag)
                .build()

        fun getAlbumUri(albumId: String): Uri = albumsUri.buildUpon()
            .appendPath(albumId)
            .build()

        fun getArtistUri(artistId: String): Uri = artistsUri.buildUpon()
            .appendPath(artistId)
            .build()

        fun getAudioUri(audioId: String): Uri = audiosUri.buildUpon()
            .appendPath(audioId)
            .build()

        fun getGenreUri(genreId: String): Uri = genresUri.buildUpon()
            .appendPath(genreId)
            .build()

        fun getPlaylistUri(playlistId: String): Uri = playlistsUri.buildUpon()
            .appendPath(playlistId)
            .build()

        fun onPlaylistsChanged() {
            playlistsChanged.value = Any()
        }

        fun onFavoritesChanged() {
            favoritesChanged.value = Any()
        }
    }

    private val providersManager = ProvidersManager(
        coroutineScope,
        providersRepository,
        ProviderType.AMPACHE,
    ) { provider, arguments ->
        val server = arguments.requireArgument(ARG_SERVER)
        val username = arguments.requireArgument(ARG_USERNAME)
        val password = arguments.requireArgument(ARG_PASSWORD)

        val ampacheClient = AmpacheClient(
            server,
            username,
            password,
            deviceIdentifier,
            {
                database.getAmpacheProviderDao().let {
                    it.getToken(provider.typeId)?.let { token ->
                        token to (it.getTokenExpiration(provider.typeId) ?: Instant.EPOCH)
                    }
                }
            },
            { token ->
                database.getAmpacheProviderDao().updateToken(
                    provider.typeId,
                    token?.first,
                    token?.second,
                )
            },
            cache
        )

        AmpacheInstance(
            ampacheClient,
            server,
        )
    }

    override fun status(
        providerIdentifier: ProviderIdentifier,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.ping().map { ping ->
            listOf(
                DataSourceInformation(
                    key = "server",
                    keyLocalizedString = LocalizedString.StringResIdLocalizedString(
                        R.string.ampache_server,
                    ),
                    value = LocalizedString.StringLocalizedString(ping.server),
                ),
                DataSourceInformation(
                    key = "version",
                    keyLocalizedString = LocalizedString.StringResIdLocalizedString(
                        R.string.ampache_version,
                    ),
                    value = LocalizedString.StringLocalizedString(ping.version),
                ),
                DataSourceInformation(
                    key = "compatible",
                    keyLocalizedString = LocalizedString.StringResIdLocalizedString(
                        R.string.ampache_compatible,
                    ),
                    value = LocalizedString.StringLocalizedString(ping.compatible),
                ),
            )
        }
    }

    override suspend fun mediaTypeOf(
        mediaItemUri: Uri,
    ) = providersManager.doWithInstanceOf(mediaItemUri) {
        with(mediaItemUri.toString()) {
            when {
                startsWith(albumsUri.toString()) -> MediaType.ALBUM
                startsWith(artistsUri.toString()) -> MediaType.ARTIST
                startsWith(audiosUri.toString()) -> MediaType.AUDIO
                startsWith(genresUri.toString()) -> MediaType.GENRE
                startsWith(playlistsUri.toString()) -> MediaType.PLAYLIST
                startsWith(favoritesUri.toString()) -> MediaType.PLAYLIST
                else -> null
            }
        }?.let {
            Result.Success(it)
        } ?: Result.Failure(Error.NOT_FOUND)
    }.getOrNull()

    override fun providerOf(mediaItemUri: Uri) = providersManager.providerOf(mediaItemUri)

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        val mostPlayedAlbums = client.stats(
            type = "album",
            filter = "frequent",
            limit = 10,
        ).map { stats ->
            ActivityTab(
                id = "most_played_albums",
                title = LocalizedString.StringResIdLocalizedString(
                    R.string.activity_most_played_albums,
                ),
                items = stats.album.orEmpty().map { it.toMediaItem() },
            )
        }

        val randomAlbums = client.stats(
            type = "album",
            filter = "random",
            limit = 10,
        ).map { stats ->
            ActivityTab(
                id = "random_albums",
                title = LocalizedString.StringResIdLocalizedString(
                    R.string.activity_random_albums,
                ),
                items = stats.album.orEmpty().map { it.toMediaItem() },
            )
        }

        val randomSongs = client.stats(
            type = "song",
            filter = "random",
            limit = 10,
        ).map { stats ->
            ActivityTab(
                id = "random_songs",
                title = LocalizedString.StringResIdLocalizedString(
                    R.string.activity_random_songs,
                ),
                items = stats.song.orEmpty().map { it.toMediaItem() },
            )
        }

        Result.Success(
            listOf(
                mostPlayedAlbums,
                randomAlbums,
                randomSongs,
            ).mapNotNull {
                (it as? Result.Success)?.data?.takeIf { activityTab ->
                    activityTab.items.isNotEmpty()
                }
            }
        )
    }

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.albums(
            sort = formatSortValue(
                when (sortingRule.strategy) {
                    SortingStrategy.ARTIST_NAME -> "artist"
                    SortingStrategy.CREATION_DATE -> "year"
                    SortingStrategy.NAME -> "name"
                    else -> null
                },
                sortingRule.reverse,
            ),
        ).map { albums ->
            albums.album.map { it.toMediaItem() }
        }
    }

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.artists(
            sort = formatSortValue(
                when (sortingRule.strategy) {
                    SortingStrategy.CREATION_DATE -> "yearformed"
                    SortingStrategy.NAME -> "name"
                    else -> null
                },
                sortingRule.reverse,
            ),
        ).map { artists ->
            artists.artist.map { it.toMediaItem() }
        }
    }

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.songs(
            sort = formatSortValue(
                when (sortingRule.strategy) {
                    SortingStrategy.ARTIST_NAME -> "artist"
                    SortingStrategy.CREATION_DATE -> "year"
                    SortingStrategy.NAME -> "name"
                    else -> null
                },
                sortingRule.reverse,
            ),
        ).map { songs ->
            songs.song.map { it.toMediaItem() }
        }
    }

    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.genres(
            sort = formatSortValue(
                when (sortingRule.strategy) {
                    SortingStrategy.NAME -> "name"
                    else -> null
                },
                sortingRule.reverse,
            ),
        ).map { genres ->
            genres.genre.map { it.toMediaItem() }
        }
    }

    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        playlistsChanged.mapLatest {
            client.playlists(
                sort = formatSortValue(
                    when (sortingRule.strategy) {
                        SortingStrategy.CREATION_DATE -> "date"
                        SortingStrategy.MODIFICATION_DATE -> "last_update"
                        SortingStrategy.NAME -> "name"
                        else -> null
                    },
                    sortingRule.reverse,
                ),
            ).map { playlists ->
                buildList {
                    add(favoritesPlaylist)

                    playlists.playlist.forEach {
                        add(it.toMediaItem())
                    }
                }
            }
        }
    }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        client.searchGroup(
            "or",
            listOf(
                Triple("name", 0, "%$query%*"),
            ),
            type = "all",
            limit = 10,
        ).map { search ->
            buildList {
                search.search.song?.forEach {
                    add(it.toMediaItem())
                }

                search.search.album?.forEach {
                    add(it.toMediaItem())
                }

                search.search.artist?.forEach {
                    add(it.toMediaItem())
                }

                search.search.genre?.forEach {
                    add(it.toMediaItem())
                }

                search.search.playlist?.forEach {
                    add(it.toMediaItem())
                }
            }
        }.let { result ->
            when (result) {
                is Result.Success -> result

                is Result.Failure -> Result.Success(
                    buildList {
                        client.songs(
                            filter = query,
                            exact = false,
                            limit = 10,
                        ).getOrNull()?.song?.forEach {
                            add(it.toMediaItem())
                        }

                        client.albums(
                            filter = query,
                            exact = false,
                            limit = 10,
                        ).getOrNull()?.album?.forEach {
                            add(it.toMediaItem())
                        }

                        client.artists(
                            filter = query,
                            exact = false,
                            limit = 10,
                        ).getOrNull()?.artist?.forEach {
                            add(it.toMediaItem())
                        }
                    }
                )
            }
        }
    }

    override fun audio(audioUri: Uri) = providersManager.flatMapWithInstanceOf(audioUri) {
        favoritesChanged.mapLatest {
            val id = audioUri.lastPathSegment!!
            client.song(id).map { song ->
                song.toMediaItem()
            }
        }
    }

    override fun album(albumUri: Uri) = providersManager.flatMapWithInstanceOf(albumUri) {
        favoritesChanged.mapLatest {
            val id = albumUri.lastPathSegment!!
            client.album(id, includeSongs = true).map { album ->
                album.toMediaItem() to album.tracks.orEmpty().map { it.toMediaItem() }
            }
        }
    }

    override fun artist(artistUri: Uri) = providersManager.mapWithInstanceOf(artistUri) {
        val id = artistUri.lastPathSegment!!
        client.artist(id, includeAlbums = true).map { artist ->
            artist.toMediaItem() to ArtistWorks(
                albums = artist.albums.orEmpty().map { it.toMediaItem() },
                appearsInAlbum = listOf(),
                appearsInPlaylist = listOf(),
            )
        }
    }

    override fun genre(genreUri: Uri) = providersManager.flatMapWithInstanceOf(genreUri) {
        favoritesChanged.mapLatest {
            val id = genreUri.lastPathSegment!!
            client.genre(id).map { genre ->
                genre.toMediaItem() to GenreContent(
                    appearsInAlbums = client.genreAlbums(
                        id
                    ).getOrNull()?.album.orEmpty().map { it.toMediaItem() },
                    appearsInPlaylists = listOf(),
                    audios = client.genreSongs(
                        id
                    ).getOrNull()?.song.orEmpty().map { it.toMediaItem() },
                )
            }
        }
    }

    override fun playlist(playlistUri: Uri) = providersManager.flatMapWithInstanceOf(playlistUri) {
        when (playlistUri) {
            favoritesUri -> favoritesChanged.mapLatest {
                client.stats(
                    type = "song",
                    filter = "flagged",
                ).map { stats ->
                    favoritesPlaylist to stats.song.orEmpty().map { it.toMediaItem() }
                }
            }

            else -> combine(favoritesChanged, playlistsChanged) {
                val id = playlistUri.lastPathSegment!!
                client.playlist(id).flatMap { playlist ->
                    client.playlistSongs(id).map { songs ->
                        playlist.toMediaItem() to songs.song.map { it.toMediaItem() }
                    }
                }
            }
        }
    }

    override fun audioPlaylistsStatus(audioUri: Uri) = audioUri.lastPathSegment!!.let { audioId ->
        providersManager.flatMapWithInstanceOf(audioUri) {
            combine(
                favoritesChanged.mapLatest { _ ->
                    val starred = client.song(audioId).getOrNull()?.flag ?: false
                    favoritesPlaylist to starred
                },
                playlistsChanged.mapLatest { _ ->
                    client.playlists().map { playlists ->
                        playlists.playlist.map { playlist ->
                            val inPlaylist = client.playlistSongs(playlist.id).map {
                                it.song.any { child -> child.id == audioId }
                            }

                            playlist.toMediaItem() to (inPlaylist.getOrNull() ?: false)
                        }
                    }
                },
            ) { favoriteToAudio, playlistToAudio ->
                playlistToAudio.map { playlists ->
                    buildList {
                        add(favoriteToAudio)

                        addAll(playlists)
                    }
                }
            }
        }
    }

    override fun lyrics(audioUri: Uri) = providersManager.mapWithInstanceOf(audioUri) {
        client.getLyrics(audioUri.lastPathSegment!!, plugins = true).map {
            it.toModel()
        }
    }

    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ) = providersManager.doWithInstanceOf(providerIdentifier) {
        client.playlistCreate(name).map { playlist ->
            onPlaylistsChanged()
            getPlaylistUri(playlist.id)
        }
    }

    override suspend fun renamePlaylist(
        playlistUri: Uri,
        name: String,
    ) = providersManager.doWithInstanceOf(playlistUri) {
        when (playlistUri) {
            favoritesUri -> Result.Failure(Error.IO)
            else -> client.playlistEdit(playlistUri.lastPathSegment!!, name = name).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun deletePlaylist(
        playlistUri: Uri,
    ) = providersManager.doWithInstanceOf(playlistUri) {
        when (playlistUri) {
            favoritesUri -> Result.Failure(Error.IO)
            else -> client.playlistDelete(playlistUri.lastPathSegment!!).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ) = providersManager.doWithInstanceOf(playlistUri, audioUri) {
        when (playlistUri) {
            favoritesUri -> setFavorite(audioUri, true)
            else -> client.playlistAdd(
                playlistUri.lastPathSegment!!,
                id = audioUri.lastPathSegment!!,
                type = "song",
                check = true,
            ).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ) = providersManager.doWithInstanceOf(playlistUri) {
        when (playlistUri) {
            favoritesUri -> setFavorite(audioUri, false)
            else -> client.playlistRemoveSong(
                playlistUri.lastPathSegment!!,
                song = audioUri.lastPathSegment!!
            ).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun onAudioPlayed(
        audioUri: Uri,
        positionMs: Long,
    ): MediaRequestStatus<Unit> = Result.Success(Unit)

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean,
    ) = providersManager.doWithInstanceOf(audioUri) {
        val id = audioUri.lastPathSegment!!
        client.flag(
            type = "song",
            filter = id,
            flag = isFavorite,
        ).map {
            onFavoritesChanged()
        }
    }

    /**
     * Format a sort value for the Ampache API.
     */
    private fun formatSortValue(key: String?, reverse: Boolean) = key?.let { key ->
        val order = when (reverse) {
            true -> "desc"
            false -> "asc"
        }

        "$key,$order"
    }

    companion object {
        private const val ALBUMS_PATH = "albums"
        private const val ARTISTS_PATH = "artists"
        private const val AUDIOS_PATH = "audio"
        private const val GENRES_PATH = "genres"
        private const val PLAYLISTS_PATH = "playlists"

        private const val FAVORITES_PATH = "favorites"

        val ARG_SERVER = ProviderArgument(
            "server",
            String::class,
            R.string.provider_argument_server,
            required = true,
            hidden = false,
            validate = {
                when (it.toHttpUrlOrNull()) {
                    null -> ProviderArgument.ValidationError(
                        "Invalid URL",
                        R.string.provider_argument_validation_error_malformed_http_uri,
                    )

                    else -> null
                }
            }
        )

        val ARG_USERNAME = ProviderArgument(
            "username",
            String::class,
            R.string.provider_argument_username,
            required = true,
            hidden = false,
        )

        val ARG_PASSWORD = ProviderArgument(
            "password",
            String::class,
            R.string.provider_argument_password,
            required = true,
            hidden = true,
            defaultValue = "",
        )
    }
}
