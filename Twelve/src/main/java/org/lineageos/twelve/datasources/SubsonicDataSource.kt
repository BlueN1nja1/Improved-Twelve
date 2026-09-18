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
import org.lineageos.twelve.datasources.subsonic.SubsonicClient
import org.lineageos.twelve.datasources.subsonic.models.AlbumID3
import org.lineageos.twelve.datasources.subsonic.models.ArtistID3
import org.lineageos.twelve.datasources.subsonic.models.Child
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

/**
 * Subsonic based data source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubsonicDataSource(
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository,
    cache: Cache? = null,
) : MediaDataSource {
    private class SubsonicInstance(
        server: String,
        val subsonicClient: SubsonicClient,
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

        fun AlbumID3.toMediaItem() = Album.Builder(getAlbumUri(id))
            .setThumbnail(
                Thumbnail.Builder()
                    .setUri(subsonicClient.getCoverArt(id).toUri())
                    .setType(Thumbnail.Type.FRONT_COVER)
                    .build()
            )
            .setTitle(name)
            .setArtistUri(artistId?.let { getArtistUri(it) })
            .setArtistName(artist)
            .setYear(year)
            .build()

        fun ArtistID3.toMediaItem() = Artist.Builder(getArtistUri(id))
            .setThumbnail(
                Thumbnail.Builder()
                    .setUri(subsonicClient.getCoverArt(id).toUri())
                    .setType(Thumbnail.Type.BAND_ARTIST_LOGO)
                    .build()
            )
            .setName(name)
            .build()

        fun Child.toMediaItem() = Audio.Builder(getAudioUri(id))
            .setThumbnail(
                albumId?.let {
                    Thumbnail.Builder()
                        .setUri(subsonicClient.getCoverArt(it).toUri())
                        .setType(Thumbnail.Type.FRONT_COVER)
                        .build()
                }
            )
            .setPlaybackUri(subsonicClient.stream(id).toUri())
            .setMimeType(contentType)
            .setTitle(title)
            .setType(type.toAudioType())
            .setDurationMs(duration?.toLong()?.let { it * 1000 })
            .setArtistUri(artistId?.let { getArtistUri(it) })
            .setArtistName(artist)
            .setAlbumUri(albumId?.let { getAlbumUri(it) })
            .setAlbumTitle(album)
            .setDiscNumber(discNumber)
            .setTrackNumber(track)
            .setGenreUri(genre?.let { getGenreUri(it) })
            .setGenreName(genre)
            .setYear(year)
            .setIsFavorite(starred != null)
            .build()

        fun org.lineageos.twelve.datasources.subsonic.models.Genre.toMediaItem() =
            Genre.Builder(getGenreUri(value))
                .setName(value)
                .build()

        fun org.lineageos.twelve.datasources.subsonic.models.Playlist.toMediaItem() =
            Playlist.Builder(getPlaylistUri(id))
                .setName(name)
                .build()

        fun org.lineageos.twelve.datasources.subsonic.models.MediaType?.toAudioType() = when (
            this
        ) {
            org.lineageos.twelve.datasources.subsonic.models.MediaType.MUSIC -> Audio.Type.MUSIC
            org.lineageos.twelve.datasources.subsonic.models.MediaType.PODCAST -> Audio.Type.PODCAST
            org.lineageos.twelve.datasources.subsonic.models.MediaType.AUDIOBOOK -> Audio.Type.AUDIOBOOK
            org.lineageos.twelve.datasources.subsonic.models.MediaType.VIDEO -> throw Exception(
                "Invalid media type, got VIDEO"
            )

            else -> Audio.Type.MUSIC
        }

        fun org.lineageos.twelve.datasources.subsonic.models.LyricsList.toModel() =
            structuredLyrics.firstOrNull()?.let { structuredLyrics ->
                val offset = structuredLyrics.offset ?: 0

                Lyrics.Builder()
                    .apply {
                        structuredLyrics.line.forEach { line ->
                            val startMs = line.start?.let { start -> start + offset }

                            addLine(
                                text = line.value,
                                startMs = startMs
                            )
                        }
                    }
                    .build()
            }

        fun getAlbumUri(albumId: String): Uri = albumsUri.buildUpon()
            .appendPath(albumId)
            .build()

        fun getArtistUri(artistId: String): Uri = artistsUri.buildUpon()
            .appendPath(artistId)
            .build()

        fun getAudioUri(audioId: String): Uri = audiosUri.buildUpon()
            .appendPath(audioId)
            .build()

        fun getGenreUri(genre: String): Uri = genresUri.buildUpon()
            .appendPath(genre)
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
        ProviderType.SUBSONIC,
    ) { _, arguments ->
        val server = arguments.requireArgument(ARG_SERVER)
        val username = arguments.requireArgument(ARG_USERNAME)
        val password = arguments.requireArgument(ARG_PASSWORD)
        val useLegacyAuthentication = arguments.requireArgument(ARG_USE_LEGACY_AUTHENTICATION)

        val subsonicClient = SubsonicClient(
            server,
            username,
            password,
            "Twelve",
            useLegacyAuthentication,
            cache,
        )

        SubsonicInstance(
            server,
            subsonicClient,
        )
    }

    override fun status(
        providerIdentifier: ProviderIdentifier,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        val ping = subsonicClient.ping()
        val license = subsonicClient.getLicense().getOrNull()

        ping.map {
            listOfNotNull(
                DataSourceInformation(
                    "version",
                    LocalizedString.StringResIdLocalizedString(
                        R.string.subsonic_version,
                    ),
                    LocalizedString.StringResIdLocalizedString(
                        R.string.subsonic_version_format,
                        listOf(it.version.major, it.version.minor, it.version.revision)
                    )
                ),
                it.type?.let { type ->
                    DataSourceInformation(
                        "server_type",
                        LocalizedString.StringResIdLocalizedString(
                            R.string.subsonic_server_type,
                        ),
                        LocalizedString.StringLocalizedString(type)
                    )
                },
                it.serverVersion?.let { serverVersion ->
                    DataSourceInformation(
                        "server_version",
                        LocalizedString.StringResIdLocalizedString(
                            R.string.subsonic_server_version,
                        ),
                        LocalizedString.StringLocalizedString(serverVersion)
                    )
                },
                it.openSubsonic?.let { openSubsonic ->
                    DataSourceInformation(
                        "supports_opensubsonic",
                        LocalizedString.StringResIdLocalizedString(
                            R.string.subsonic_supports_opensubsonic,
                        ),
                        LocalizedString.of(openSubsonic)
                    )
                },
                license?.let { lic ->
                    DataSourceInformation(
                        "license",
                        LocalizedString.StringResIdLocalizedString(R.string.subsonic_license),
                        LocalizedString.StringResIdLocalizedString(
                            when (lic.valid) {
                                true -> R.string.subsonic_license_valid
                                false -> R.string.subsonic_license_invalid
                            }
                        )
                    )
                },
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
                mediaItemUri == favoritesUri -> MediaType.PLAYLIST
                else -> null
            }?.let {
                Result.Success(it)
            } ?: Result.Failure(Error.NOT_FOUND)
        }
    }.getOrNull()

    override fun providerOf(mediaItemUri: Uri) = providersManager.providerOf(mediaItemUri)

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        val mostPlayedAlbums = subsonicClient.getAlbumList2(
            "frequent",
            10
        ).map { albumList2 ->
            ActivityTab(
                "most_played_albums",
                LocalizedString.StringResIdLocalizedString(
                    R.string.activity_most_played_albums,
                ),
                albumList2.album.sortedByDescending { it.playCount }.map { it.toMediaItem() }
            )
        }

        val randomAlbums = subsonicClient.getAlbumList2(
            "random",
            10
        ).map { albumList2 ->
            ActivityTab(
                "random_albums",
                LocalizedString.StringResIdLocalizedString(
                    R.string.activity_random_albums,
                ),
                albumList2.album.map { it.toMediaItem() }
            )
        }

        val randomSongs = subsonicClient.getRandomSongs(20).map { songs ->
            ActivityTab(
                "random_songs",
                LocalizedString.StringResIdLocalizedString(
                    R.string.activity_random_songs,
                ),
                songs.song.map { it.toMediaItem() }
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
        subsonicClient.getAlbumList2(
            "alphabeticalByName",
            500
        ).map { albumList2 ->
            albumList2.album.maybeSortedBy(
                sortingRule.reverse,
                when (sortingRule.strategy) {
                    SortingStrategy.ARTIST_NAME -> { album -> album.artist }
                    SortingStrategy.CREATION_DATE -> { album -> album.year }
                    SortingStrategy.NAME -> { album -> album.name }
                    SortingStrategy.PLAY_COUNT -> { album -> album.playCount }
                    else -> null
                }
            ).map { it.toMediaItem() }
        }
    }

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        subsonicClient.getArtists().map { artistsID3 ->
            artistsID3.index.flatMap { it.artist }.maybeSortedBy(
                sortingRule.reverse,
                when (sortingRule.strategy) {
                    SortingStrategy.NAME -> { artist -> artist.name }

                    else -> null
                }
            ).map { it.toMediaItem() }
        }
    }

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        subsonicClient.getRandomSongs(size = 500).map { randomSongs ->
            randomSongs.song.maybeSortedBy(
                sortingRule.reverse,
                when (sortingRule.strategy) {
                    SortingStrategy.ARTIST_NAME -> { child -> child.artist }
                    SortingStrategy.CREATION_DATE -> { child -> child.year }
                    SortingStrategy.NAME -> { child -> child.title }
                    SortingStrategy.PLAY_COUNT -> { child -> child.playCount }
                    else -> null
                }
            ).map { it.toMediaItem() }
        }
    }

    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        subsonicClient.getGenres().map { genres ->
            genres.genre.maybeSortedBy(
                sortingRule.reverse,
                when (sortingRule.strategy) {
                    SortingStrategy.NAME -> { genre -> genre.value }

                    else -> null
                }
            ).map { it.toMediaItem() }
        }
    }

    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule
    ) = providersManager.flatMapWithInstanceOf(providerIdentifier) {
        playlistsChanged.mapLatest {
            subsonicClient.getPlaylists().map { playlists ->
                buildList {
                    add(favoritesPlaylist)

                    playlists.playlist.maybeSortedBy(
                        sortingRule.reverse,
                        when (sortingRule.strategy) {
                            SortingStrategy.CREATION_DATE -> { playlist ->
                                playlist.created
                            }

                            SortingStrategy.MODIFICATION_DATE -> { playlist ->
                                playlist.changed
                            }

                            SortingStrategy.NAME -> { playlist ->
                                playlist.name
                            }

                            else -> null
                        }
                    ).forEach {
                        add(it.toMediaItem())
                    }
                }
            }
        }
    }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        subsonicClient.search3("%${query}%").map { searchResult3 ->
            searchResult3.song.orEmpty().map { it.toMediaItem() } +
                    searchResult3.artist.orEmpty().map { it.toMediaItem() } +
                    searchResult3.album.orEmpty().map { it.toMediaItem() }
        }
    }

    override fun audio(audioUri: Uri) = providersManager.flatMapWithInstanceOf(audioUri) {
        favoritesChanged.mapLatest {
            subsonicClient.getSong(audioUri.lastPathSegment!!).map { child ->
                child.toMediaItem()
            }
        }
    }

    override fun album(albumUri: Uri) = providersManager.mapWithInstanceOf(albumUri) {
        subsonicClient.getAlbum(albumUri.lastPathSegment!!).map { albumWithSongsID3 ->
            albumWithSongsID3.toAlbumID3().toMediaItem() to albumWithSongsID3.song.map {
                it.toMediaItem()
            }
        }
    }

    override fun artist(artistUri: Uri) = providersManager.mapWithInstanceOf(artistUri) {
        subsonicClient.getArtist(artistUri.lastPathSegment!!).map { artistWithAlbumsID3 ->
            artistWithAlbumsID3.toArtistID3().toMediaItem() to ArtistWorks(
                albums = artistWithAlbumsID3.album.map { it.toMediaItem() },
                appearsInAlbum = listOf(),
                appearsInPlaylist = listOf(),
            )
        }
    }

    override fun genre(genreUri: Uri) = providersManager.mapWithInstanceOf(genreUri) {
        val genreName = genreUri.lastPathSegment!!

        val appearsInAlbums = subsonicClient.getAlbumList2(
            "byGenre",
            size = 500,
            genre = genreName
        ).map { albumList2 ->
            albumList2.album.map { it.toMediaItem() }
        }.let {
            when (it) {
                is Result.Success -> it.data
                else -> null
            }
        }

        val audios = subsonicClient.getSongsByGenre(genreName).map { songs ->
            songs.song.map { it.toMediaItem() }
        }.let {
            when (it) {
                is Result.Success -> it.data
                else -> null
            }
        }

        val exists = listOf(
            appearsInAlbums,
            audios,
        ).any { it != null }

        if (exists) {
            Result.Success(
                Genre.Builder(genreUri).setName(genreName).build() to GenreContent(
                    appearsInAlbums.orEmpty(),
                    listOf(),
                    audios.orEmpty(),
                )
            )
        } else {
            Result.Failure(Error.NOT_FOUND)
        }
    }

    override fun playlist(playlistUri: Uri) = providersManager.flatMapWithInstanceOf(playlistUri) {
        when {
            playlistUri == favoritesUri -> favoritesChanged.mapLatest {
                subsonicClient.getStarred2().map {
                    favoritesPlaylist to it.song.orEmpty().map { child ->
                        child.toMediaItem()
                    }
                }
            }

            else -> playlistsChanged.mapLatest {
                subsonicClient.getPlaylist(playlistUri.lastPathSegment!!).map {
                    it.toPlaylist().toMediaItem() to it.entry.orEmpty().map { child ->
                        child.toMediaItem()
                    }
                }
            }
        }
    }

    override fun audioPlaylistsStatus(audioUri: Uri) = audioUri.lastPathSegment!!.let { audioId ->
        providersManager.flatMapWithInstanceOf(audioUri) {
            combine(
                favoritesChanged.mapLatest { _ ->
                    val starred = subsonicClient.getSong(audioId).getOrNull()?.starred != null
                    favoritesPlaylist to starred
                },
                playlistsChanged.mapLatest { _ ->
                    subsonicClient.getPlaylists().map { playlists ->
                        playlists.playlist.map { playlist ->
                            val inPlaylist = subsonicClient.getPlaylist(playlist.id).map {
                                it.entry.orEmpty().any { child -> child.id == audioId }
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
        val audioId = audioUri.lastPathSegment!!

        subsonicClient.getLyricsBySongId(audioId).map {
            it.toModel()
        }.flatMap { lyrics ->
            lyrics?.let { Result.Success(it) } ?: Result.Failure(Error.NOT_FOUND)
        }
    }

    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ) = providersManager.doWithInstanceOf(providerIdentifier) {
        subsonicClient.createPlaylist(
            null, name, listOf()
        ).map { playlistWithSongs ->
            onPlaylistsChanged()
            getPlaylistUri(playlistWithSongs.id)
        }
    }

    override suspend fun renamePlaylist(
        playlistUri: Uri,
        name: String,
    ) = providersManager.doWithInstanceOf(playlistUri) {
        when {
            playlistUri == favoritesUri -> Result.Failure(Error.IO)
            else -> subsonicClient.updatePlaylist(playlistUri.lastPathSegment!!, name).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun deletePlaylist(
        playlistUri: Uri,
    ) = providersManager.doWithInstanceOf(playlistUri) {
        when {
            playlistUri == favoritesUri -> Result.Failure(Error.IO)
            else -> subsonicClient.deletePlaylist(
                playlistUri.lastPathSegment!!
            ).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ) = providersManager.doWithInstanceOf(playlistUri, audioUri) {
        when {
            playlistUri == favoritesUri -> setFavorite(audioUri, true)
            else -> subsonicClient.updatePlaylist(
                playlistUri.lastPathSegment!!,
                songIdsToAdd = listOf(audioUri.lastPathSegment!!)
            ).map {
                onPlaylistsChanged()
            }
        }
    }

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri
    ) = providersManager.doWithInstanceOf(playlistUri, audioUri) {
        when {
            playlistUri == favoritesUri -> setFavorite(audioUri, false)
            else -> subsonicClient.getPlaylist(
                playlistUri.lastPathSegment!!
            ).map { playlistWithSongs ->
                val audioId = audioUri.lastPathSegment!!

                val audioIndexes =
                    playlistWithSongs.entry.orEmpty().mapIndexedNotNull { index, child ->
                        index.takeIf { child.id == audioId }
                    }

                if (audioIndexes.isNotEmpty()) {
                    subsonicClient.updatePlaylist(
                        playlistUri.lastPathSegment!!,
                        songIndexesToRemove = audioIndexes,
                    ).map {
                        onPlaylistsChanged()
                    }
                }
            }
        }
    }

    override suspend fun onAudioPlayed(
        audioUri: Uri,
        positionMs: Long,
    ): MediaRequestStatus<Unit> = providersManager.doWithInstanceOf(audioUri) {
        subsonicClient.scrobble(ids = listOf(audioUri.lastPathSegment!!), submission = false)
        Result.Success(Unit)
    }

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean
    ) = providersManager.doWithInstanceOf(audioUri) {
        when (isFavorite) {
            true -> subsonicClient.star(ids = listOf(audioUri.lastPathSegment!!))
            false -> subsonicClient.unstar(ids = listOf(audioUri.lastPathSegment!!))
        }.map {
            onFavoritesChanged()
        }
    }

    /**
     * Apply [List.asReversed] if [condition] is true.
     * Reminder that [List.asReversed] returns a new list view, thus being O(1).
     */
    private fun <T> List<T>.asMaybeReversed(
        condition: Boolean,
    ) = when (condition) {
        true -> asReversed()
        else -> this
    }

    /**
     * Sort this list by the [selector] and apply [List.asReversed] if [reverse] is true.
     * If [selector] is null, return the original list.
     */
    private fun <T> List<T>.maybeSortedBy(
        reverse: Boolean,
        selector: ((T) -> Comparable<*>?)?,
    ) = selector?.let {
        @Suppress("UNCHECKED_CAST")
        sortedBy { t -> it(t) as? Comparable<Any?> }.asMaybeReversed(reverse)
    } ?: this

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
        )

        val ARG_USE_LEGACY_AUTHENTICATION = ProviderArgument(
            "use_legacy_authentication",
            Boolean::class,
            R.string.provider_argument_use_legacy_authentication,
            required = true,
            hidden = false,
            defaultValue = false,
        )
    }
}
