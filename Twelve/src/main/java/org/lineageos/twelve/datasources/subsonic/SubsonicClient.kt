/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.subsonic

import androidx.core.net.toUri
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lineageos.twelve.datasources.subsonic.SubsonicClient.Companion.SUBSONIC_API_VERSION
import org.lineageos.twelve.datasources.subsonic.models.ResponseRoot
import org.lineageos.twelve.datasources.subsonic.models.ResponseStatus
import org.lineageos.twelve.datasources.subsonic.models.SubsonicResponse
import org.lineageos.twelve.datasources.subsonic.models.Version
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.utils.Api
import org.lineageos.twelve.utils.ApiError
import org.lineageos.twelve.utils.ApiRequest
import org.lineageos.twelve.utils.toError
import org.lineageos.twelve.datasources.subsonic.models.Error as SubsonicError

/**
 * Subsonic client. Compliant with version [SUBSONIC_API_VERSION].
 *
 * @param server The base URL of the server
 * @param username The username to use
 * @param password The password to use
 * @param clientName The name of the client to use for requests
 * @param useLegacyAuthentication Whether to use legacy authentication or not (token authentication)
 * @param cache OkHttp's [Cache]
 */
class SubsonicClient(
    private val server: String,
    private val username: String,
    private val password: String,
    private val clientName: String,
    private val useLegacyAuthentication: Boolean,
    private val cache: Cache? = null,
) {
    private val interceptor = SubsonicAuthInterceptor(
        SUBSONIC_API_VERSION,
        username,
        password,
        clientName,
        useLegacyAuthentication
    )

    private val okHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addInterceptor(interceptor)
        .build()

    private val serverUri = server.toUri().buildUpon().apply {
        appendPath("rest")
    }.build()

    private val api = Api(okHttpClient, serverUri)

    /**
     * Used to test connectivity with the server. Takes no extra parameters.
     *
     * @since 1.0.0
     */
    suspend fun ping() = ApiRequest.get<ResponseRoot>(
        listOf("ping")
    ).execute(api).mapResponse { this }

    /**
     * Get details about the software license. Takes no extra parameters. Please note that access to
     * the REST API requires that the server has a valid license (after a 30-day trial period). To
     * get a license key you must upgrade to Subsonic Premium.
     *
     * @since 1.0.0
     */
    suspend fun getLicense() = ApiRequest.get<ResponseRoot>(
        listOf("getLicense")
    ).execute(api).mapResponse(SubsonicResponse::license)

    /**
     * Returns all configured top-level music folders. Takes no extra parameters.
     *
     * @since 1.0.0
     */
    suspend fun getMusicFolders() = ApiRequest.get<ResponseRoot>(
        listOf("getMusicFolders")
    ).execute(api).mapResponse(SubsonicResponse::musicFolders)

    /**
     * Returns an indexed structure of all artists.
     *
     * @since 1.0.0
     * @param musicFolderId If specified, only return artists in the music folder with the given ID.
     *   See [getMusicFolders].
     * @param ifModifiedSince If specified, only return a result if the artist collection has
     *   changed since the given time (in milliseconds since 1 Jan 1970).
     */
    suspend fun getIndexes(
        musicFolderId: Int? = null,
        ifModifiedSince: Long? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getIndexes"),
        queryParameters = listOf(
            "musicFolderId" to musicFolderId,
            "ifModifiedSince" to ifModifiedSince,
        )
    ).execute(api).mapResponse(SubsonicResponse::indexes)

    /**
     * Returns a listing of all files in a music directory. Typically used to get list of albums for
     * an artist, or list of songs for an album.
     *
     * @since 1.0.0
     * @param id A string which uniquely identifies the music folder. Obtained by calls to
     *   getIndexes or getMusicDirectory.
     */
    suspend fun getMusicDirectory(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getMusicDirectory"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::directory)

    /**
     * Returns all genres.
     *
     * @since 1.9.0
     */
    suspend fun getGenres() = ApiRequest.get<ResponseRoot>(
        listOf("getGenres")
    ).execute(api).mapResponse(SubsonicResponse::genres)

    /**
     * Similar to getIndexes, but organizes music according to ID3 tags.
     *
     * @since 1.8.0
     * @param musicFolderId If specified, only return artists in the music folder with the given ID.
     *   See [getMusicFolders].
     */
    suspend fun getArtists(
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getArtists"),
        queryParameters = listOf(
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::artists)

    /**
     * Returns details for an artist, including a list of albums. This method organizes music
     * according to ID3 tags.
     *
     * @since 1.8.0
     * @param id The artist ID.
     */
    suspend fun getArtist(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getArtist"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::artist)

    /**
     * Returns details for an album, including a list of songs. This method organizes music
     * according to ID3 tags.
     *
     * @since 1.8.0
     * @param id The album ID.
     */
    suspend fun getAlbum(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getAlbum"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::album)

    /**
     * Returns details for a song.
     *
     * @since 1.8.0
     * @param id The song ID.
     */
    suspend fun getSong(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getSong"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::song)

    /**
     * Returns all video files.
     *
     * @since 1.8.0
     */
    suspend fun getVideos() = ApiRequest.get<ResponseRoot>(
        listOf("getVideos")
    ).execute(api).mapResponse(SubsonicResponse::videos)

    /**
     * Returns details for a video, including information about available audio tracks, subtitles
     * (captions) and conversions.
     *
     * @since 1.14.0
     * @param id The video ID.
     */
    suspend fun getVideoInfo(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getVideoInfo"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::videoInfo)

    /**
     * Returns artist info with biography, image URLs and similar artists, using data from last.fm.
     *
     * @since 1.11.0
     * @param id The artist, album or song ID.
     * @param count Max number of similar artists to return.
     * @param includeNotPresent Whether to return artists that are not present in the media library.
     */
    suspend fun getArtistInfo(
        id: String,
        count: Int? = null,
        includeNotPresent: Boolean? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getArtistInfo"),
        queryParameters = listOf(
            "id" to id,
            "count" to count,
            "includeNotPresent" to includeNotPresent,
        )
    ).execute(api).mapResponse(SubsonicResponse::artistInfo)

    /**
     * Similar to [getArtistInfo], but organizes music according to ID3 tags.
     *
     * @since 1.11.0
     * @param id The artist ID.
     * @param count Max number of similar artists to return.
     * @param includeNotPresent Whether to return artists that are not present in the media library.
     */
    suspend fun getArtistInfo2(
        id: String,
        count: Int? = null,
        includeNotPresent: Boolean? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getArtistInfo2"),
        queryParameters = listOf(
            "id" to id,
            "count" to count,
            "includeNotPresent" to includeNotPresent,
        )
    ).execute(api).mapResponse(SubsonicResponse::artistInfo2)

    /**
     * Returns album notes, image URLs etc, using data from last.fm.
     *
     * @since 1.14.0
     * @param id The album or song ID.
     */
    suspend fun getAlbumInfo(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getAlbumInfo"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::albumInfo)

    /**
     * Similar to [getAlbumInfo], but organizes music according to ID3 tags.
     *
     * @since 1.14.0
     * @param id The album ID.
     */
    suspend fun getAlbumInfo2(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getAlbumInfo2"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::albumInfo)

    /**
     * Returns a random collection of songs from the given artist and similar artists, using data
     * from last.fm. Typically used for artist radio features.
     *
     * @since 1.11.0
     * @param id The artist, album or song ID.
     * @param count Max number of songs to return.
     */
    suspend fun getSimilarSongs(
        id: String,
        count: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getSimilarSongs"),
        queryParameters = listOf(
            "id" to id,
            "count" to count,
        )
    ).execute(api).mapResponse(SubsonicResponse::similarSongs)

    /**
     * Similar to [getSimilarSongs], but organizes music according to ID3 tags.
     *
     * @since 1.11.0
     * @param id The artist ID.
     * @param count Max number of songs to return.
     */
    suspend fun getSimilarSongs2(
        id: String,
        count: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getSimilarSongs2"),
        queryParameters = listOf(
            "id" to id,
            "count" to count,
        )
    ).execute(api).mapResponse(SubsonicResponse::similarSongs2)

    /**
     * Returns top songs for the given artist, using data from last.fm.
     *
     * @since 1.13.0
     * @param artist The artist name.
     * @param count Max number of songs to return.
     */
    suspend fun getTopSongs(
        artist: String,
        count: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getTopSongs"),
        queryParameters = listOf(
            "artist" to artist,
            "count" to count,
        )
    ).execute(api).mapResponse(SubsonicResponse::topSongs)

    /**
     * Returns a list of random, newest, highest rated etc. albums. Similar to the album lists on
     * the home page of the Subsonic web interface.
     *
     * @since 1.2.0
     * @param type The list type. Must be one of the following: `random`, `newest`, `frequent`,
     *   `recent`, `starred`, `alphabeticalByName` or `alphabeticalByArtist`. Since 1.10.1 you can
     *   use `byYear` and `byGenre` to list albums in a given year range or genre.
     * @param size The number of albums to return. Max 500.
     * @param offset The list offset. Useful if you for example want to page through the list of
     *   newest albums.
     * @param fromYear The first year in the range. If [fromYear] > [toYear] a reverse chronological
     *   list is returned.
     * @param toYear The last year in the range.
     * @param genre The name of the genre, e.g., "Rock".
     * @param musicFolderId (Since 1.11.0) Only return albums in the music folder with the given ID.
     *   See [getMusicFolders].
     */
    suspend fun getAlbumList(
        type: String,
        size: Int? = null,
        offset: Int? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
        genre: String? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getAlbumList"),
        queryParameters = listOf(
            "type" to type,
            "size" to size,
            "offset" to offset,
            "fromYear" to fromYear,
            "toYear" to toYear,
            "genre" to genre,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::albumList)

    /**
     * Similar to [getAlbumList], but organizes music according to ID3 tags.
     *
     * @since 1.8.0
     * @param type The list type. Must be one of the following: `random`, `newest`, `frequent`,
     *   `recent`, `starred`, `alphabeticalByName` or `alphabeticalByArtist`. Since 1.10.1 you can
     *   use `byYear` and `byGenre` to list albums in a given year range or genre.
     * @param size The number of albums to return. Max 500.
     * @param offset The list offset. Useful if you for example want to page through the list of
     *   newest albums.
     * @param fromYear The first year in the range. If [fromYear] > [toYear] a reverse chronological
     *   list is returned.
     * @param toYear The last year in the range.
     * @param genre The name of the genre, e.g., "Rock".
     * @param musicFolderId (Since 1.12.0) Only return albums in the music folder with the given ID.
     *   See [getMusicFolders].
     */
    suspend fun getAlbumList2(
        type: String,
        size: Int? = null,
        offset: Int? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
        genre: String? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getAlbumList2"),
        queryParameters = listOf(
            "type" to type,
            "size" to size,
            "offset" to offset,
            "fromYear" to fromYear,
            "toYear" to toYear,
            "genre" to genre,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::albumList2)

    /**
     * Returns random songs matching the given criteria.
     *
     * @since 1.2.0
     * @param size The maximum number of songs to return. Max 500.
     * @param genre Only returns songs belonging to this genre.
     * @param fromYear Only return songs published after or in this year.
     * @param toYear Only return songs published before or in this year.
     * @param musicFolderId Only return songs in the music folder with the given ID. See
     *   [getMusicFolders].
     */
    suspend fun getRandomSongs(
        size: Int? = null,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getRandomSongs"),
        queryParameters = listOf(
            "size" to size,
            "genre" to genre,
            "fromYear" to fromYear,
            "toYear" to toYear,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::randomSongs)

    /**
     * Returns songs in a given genre.
     *
     * @since 1.9.0
     * @param genre The genre, as returned by [getGenres].
     * @param count The maximum number of songs to return. Max 500.
     * @param offset The offset. Useful if you want to page through the songs in a genre.
     * @param (Since 1.12.0) Only return albums in the music folder with the given ID. See
     *   [getMusicFolders].
     */
    suspend fun getSongsByGenre(
        genre: String,
        count: Int? = null,
        offset: Int? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getSongsByGenre"),
        queryParameters = listOf(
            "genre" to genre,
            "count" to count,
            "offset" to offset,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::songsByGenre)

    /**
     * Returns what is currently being played by all users. Takes no extra parameters.
     *
     * @since 1.0.0
     */
    suspend fun getNowPlaying() = ApiRequest.get<ResponseRoot>(
        listOf("getNowPlaying")
    ).execute(api).mapResponse(SubsonicResponse::nowPlaying)

    /**
     * Returns starred songs, albums and artists.
     *
     * @since 1.8.0
     * @param musicFolderId (Since 1.12.0) Only return results from the music folder with the given
     *   ID. See [getMusicFolders].
     */
    suspend fun getStarred(
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getStarred"),
        queryParameters = listOf(
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::starred)

    /**
     * Similar to [getStarred], but organizes music according to ID3 tags.
     *
     * @since 1.8.0
     * @param musicFolderId (Since 1.12.0) Only return results from the music folder with the given
     *   ID. See [getMusicFolders].
     */
    suspend fun getStarred2(
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getStarred2"),
        queryParameters = listOf(
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::starred2)

    /**
     * Returns a listing of files matching the given search criteria. Supports paging through the
     * result.
     *
     * @since 1.0.0
     * @param artist Artist to search for.
     * @param album Album to search for.
     * @param title Song title to search for.
     * @param any Searches all fields.
     * @param count Maximum number of results to return.
     * @param offset Search result offset. Used for paging.
     * @param newerThan Only return matches that are newer than this. Given as milliseconds since
     *   1970.
     */
    @Deprecated("Deprecated since 1.4.0, use search2 instead.")
    suspend fun search(
        artist: String? = null,
        album: String? = null,
        title: String? = null,
        any: String? = null,
        count: Int? = null,
        offset: Int? = null,
        newerThan: Long? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("search"),
        queryParameters = listOf(
            "artist" to artist,
            "album" to album,
            "title" to title,
            "any" to any,
            "count" to count,
            "offset" to offset,
            "newerThan" to newerThan,
        )
    ).execute(api).mapResponse(SubsonicResponse::searchResult)

    /**
     * Returns albums, artists and songs matching the given search criteria. Supports paging through the result.
     *
     * @since 1.4.0
     * @param query Search query.
     * @param artistCount Maximum number of artists to return.
     * @param artistOffset Search result offset for artists. Used for paging.
     * @param albumCount Maximum number of albums to return.
     * @param albumOffset Search result offset for albums. Used for paging.
     * @param songCount Maximum number of songs to return.
     * @param songOffset Search result offset for songs. Used for paging.
     * @param musicFolderId (Since 1.12.0) Only return results from the music folder with the given
     *   ID. See [getMusicFolders].
     */
    suspend fun search2(
        query: String,
        artistCount: Int? = null,
        artistOffset: Int? = null,
        albumCount: Int? = null,
        albumOffset: Int? = null,
        songCount: Int? = null,
        songOffset: Int? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("search2"),
        queryParameters = listOf(
            "query" to query,
            "artistCount" to artistCount,
            "artistOffset" to artistOffset,
            "albumCount" to albumCount,
            "albumOffset" to albumOffset,
            "songCount" to songCount,
            "songOffset" to songOffset,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::searchResult2)

    /**
     * Similar to [search2], but organizes music according to ID3 tags.
     *
     * @since 1.8.0
     * @param query Search query.
     * @param artistCount Maximum number of artists to return.
     * @param artistOffset Search result offset for artists. Used for paging.
     * @param albumCount Maximum number of albums to return.
     * @param albumOffset Search result offset for albums. Used for paging.
     * @param songCount Maximum number of songs to return.
     * @param songOffset Search result offset for songs. Used for paging.
     * @param musicFolderId (Since 1.12.0) Only return results from music folder with the given ID.
     *   See [getMusicFolders].
     */
    suspend fun search3(
        query: String,
        artistCount: Int? = null,
        artistOffset: Int? = null,
        albumCount: Int? = null,
        albumOffset: Int? = null,
        songCount: Int? = null,
        songOffset: Int? = null,
        musicFolderId: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("search3"),
        queryParameters = listOf(
            "query" to query,
            "artistCount" to artistCount,
            "artistOffset" to artistOffset,
            "albumCount" to albumCount,
            "albumOffset" to albumOffset,
            "songCount" to songCount,
            "songOffset" to songOffset,
            "musicFolderId" to musicFolderId,
        )
    ).execute(api).mapResponse(SubsonicResponse::searchResult3)

    /**
     * Returns all playlists a user is allowed to play.
     *
     * @since 1.0.0
     * @param username (Since 1.8.0) If specified, return playlists for this user rather than for
     *   the authenticated user. The authenticated user must have admin role if this parameter is
     *   used.
     */
    suspend fun getPlaylists(
        username: String? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getPlaylists"),
        queryParameters = listOf(
            "username" to username,
        )
    ).execute(api).mapResponse(SubsonicResponse::playlists)

    /**
     * Returns a listing of files in a saved playlist.
     *
     * @since 1.0.0
     * @param id ID of the playlist to return, as obtained by [getPlaylists].
     */
    suspend fun getPlaylist(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getPlaylist"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::playlist)

    /**
     * Creates (or updates) a playlist.
     *
     * @since 1.2.0
     * @param playlistId The playlist ID. Required if updating an existing playlist.
     * @param name The playlist name. Required if creating a new playlist.
     * @param songIds ID of a song in the playlist. Use one songId parameter for each song in the
     *   playlist.
     */
    suspend fun createPlaylist(
        playlistId: String? = null,
        name: String? = null,
        songIds: List<Int>,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("createPlaylist"),
        queryParameters = listOf(
            "playlistId" to playlistId,
            "name" to name,
            *songIds.map { "songId" to it }.toTypedArray(),
        )
    ).execute(api).mapResponse(SubsonicResponse::playlist)

    /**
     * Updates a playlist. Only the owner of a playlist is allowed to update it.
     *
     * @since 1.8.0
     * @param playlistId The playlist ID.
     * @param name The human-readable name of the playlist.
     * @param comment The playlist comment.
     * @param public `true` if the playlist should be visible to all users, `false` otherwise.
     * @param songIdsToAdd Add this song with this ID to the playlist. Multiple parameters allowed.
     * @param songIndexesToRemove Remove the song at this position in the playlist. Multiple
     *   parameters allowed.
     */
    suspend fun updatePlaylist(
        playlistId: String,
        name: String? = null,
        comment: String? = null,
        public: Boolean? = null,
        songIdsToAdd: List<String>? = null,
        songIndexesToRemove: List<Int>? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("updatePlaylist"),
        queryParameters = listOf(
            "playlistId" to playlistId,
            "name" to name,
            "comment" to comment,
            "public" to public,
            *songIdsToAdd?.map { "songIdToAdd" to it }?.toTypedArray().orEmpty(),
            *songIndexesToRemove?.map { "songIndexToRemove" to it }?.toTypedArray().orEmpty(),
        )
    ).execute(api).mapResponse(SubsonicResponse::playlist)

    /**
     * Deletes a saved playlist.
     *
     * @since 1.2.0
     * @param id ID of the playlist to delete, as obtained by [getPlaylists].
     */
    suspend fun deletePlaylist(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("deletePlaylist"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse { }

    /**
     * Streams a given media file.
     *
     * @since 1.0.0
     * @param id A string which uniquely identifies the file to stream. Obtained by calls to
     *   [getMusicDirectory].
     * @param maxBitRate (Since 1.2.0) If specified, the server will attempt to limit the bitrate to
     *   this value, in kilobits per second. If set to zero, no limit is imposed.
     * @param format (Since 1.6.0) Specifies the preferred target format (e.g., "mp3" or "flv") in
     *   case there are multiple applicable transcodings. Starting with 1.9.0 you can use the
     *   special value "raw" to disable transcoding.
     * @param timeOffset Only applicable to video streaming. If specified, start streaming at the
     *   given offset (in seconds) into the video. Typically used to implement video skipping.
     * @param size (Since 1.6.0) Only applicable to video streaming. Requested video size specified
     *   as WxH, for instance "640x480".
     * @param estimateContentLength (Since 1.8.0). If set to "true", the Content-Length HTTP header
     *   will be set to an estimated value for transcoded or downsampled media.
     * @param converted (Since 1.14.0) Only applicable to video streaming. Subsonic can optimize
     *   videos for streaming by converting them to MP4. If a conversion exists for the video in
     *   question, then setting this parameter to "true" will cause the converted video to be
     *   returned instead of the original.
     */
    fun stream(
        id: String,
        maxBitRate: Int? = null,
        format: String? = null,
        timeOffset: Int? = null,
        size: String? = null,
        estimateContentLength: Boolean? = null,
        converted: Boolean? = null,
    ) = api.buildUrl(
        listOf("stream"),
        queryParameters = listOf(
            "id" to id,
            "maxBitRate" to maxBitRate,
            "format" to format,
            "timeOffset" to timeOffset,
            "size" to size,
            "estimateContentLength" to estimateContentLength,
            "converted" to converted,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Downloads a given media file. Similar to stream, but this method returns the original media
     * data without transcoding or downsampling.
     *
     * @since 1.0.0
     * @param id A string which uniquely identifies the file to download. Obtained by calls to
     *   [getMusicDirectory].
     */
    fun download(
        id: String,
    ) = api.buildUrl(
        listOf("download"),
        queryParameters = listOf(
            "id" to id,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Creates an HLS (HTTP Live Streaming) playlist used for streaming video or audio. HLS is a
     * streaming protocol implemented by Apple and works by breaking the overall stream into a
     * sequence of small HTTP-based file downloads. It's supported by iOS and newer versions of
     * Android. This method also supports adaptive bitrate streaming, see the bitRate parameter.
     *
     * @since 1.8.0
     * @param id A string which uniquely identifies the media file to stream.
     * @param bitRate If specified, the server will attempt to limit the bitrate to this value, in
     *   kilobits per second. If this parameter is specified more than once, the server will create
     *   a variant playlist, suitable for adaptive bitrate streaming. The playlist will support
     *   streaming at all the specified bitrates. The server will automatically choose video
     *   dimensions that are suitable for the given bitrates. Since 1.9.0 you may explicitly request
     *   a certain width (480) and height (360) like so: `bitRate=1000@480x360`
     * @param audioTrack The ID of the audio track to use. See getVideoInfo for how to get the list
     *   of available audio tracks for a video.
     */
    fun hls(
        id: String,
        bitRate: String? = null,
        audioTrack: Int? = null,
    ) = api.buildUrl(
        listOf("hls"),
        queryParameters = listOf(
            "id" to id,
            "bitRate" to bitRate,
            "audioTrack" to audioTrack,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Returns captions (subtitles) for a video. Use [getVideoInfo] to get a list of available
     * captions.
     *
     * @since 1.14.0
     * @param id The ID of the video.
     * @param format Preferred captions format ("srt" or "vtt").
     */
    fun getCaptions(
        id: String,
        format: String? = null,
    ) = api.buildUrl(
        listOf("getCaptions"),
        queryParameters = listOf(
            "id" to id,
            "format" to format,
        ) + interceptor.getAuthParameters()
    )

    /***
     * Returns a cover art image.
     *
     * @since 1.0.0
     * @param id The ID of a song, album or artist.
     * @param size If specified, scale image to this size.
     */
    fun getCoverArt(
        id: String,
        size: Int? = null,
    ) = api.buildUrl(
        listOf("getCoverArt"),
        queryParameters = listOf(
            "id" to id,
            "size" to size,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Searches for and returns lyrics for a given song.
     *
     * @since 1.2.0
     * @param artist The artist name.
     * @param title The song title.
     */
    fun getLyrics(
        artist: String? = null,
        title: String? = null,
    ) = api.buildUrl(
        listOf("getLyrics"),
        queryParameters = listOf(
            "artist" to artist,
            "title" to title,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Returns the avatar (personal image) for a user.
     *
     * @since 1.8.0
     * @param username The user in question.
     */
    fun getAvatar(
        username: String,
    ) = api.buildUrl(
        listOf("getAvatar"),
        queryParameters = listOf(
            "username" to username,
        ) + interceptor.getAuthParameters()
    )

    /**
     * Attaches a star to a song, album or artist.
     *
     * @since 1.8.0
     * @param ids The ID of the file (song) or folder (album/artist) to star. Multiple parameters
     *   allowed.
     * @param albumIds The ID of an album to star. Use this rather than id if the client accesses
     *   the media collection according to ID3 tags rather than file structure. Multiple parameters
     *   allowed.
     * @param artistIds The ID of an artist to star. Use this rather than id if the client accesses
     *   the media collection according to ID3 tags rather than file structure. Multiple parameters
     *   allowed.
     */
    suspend fun star(
        ids: List<String>? = null,
        albumIds: List<String>? = null,
        artistIds: List<String>? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("star"),
        queryParameters = listOf(
            *ids?.map { "id" to it }?.toTypedArray().orEmpty(),
            *albumIds?.map { "albumId" to it }?.toTypedArray().orEmpty(),
            *artistIds?.map { "artistId" to it }?.toTypedArray().orEmpty(),
        )
    ).execute(api).mapResponse { }

    /**
     * Removes the star from a song, album or artist.
     *
     * @since 1.8.0
     * @param ids The ID of the file (song) or folder (album/artist) to unstar. Multiple parameters
     *   allowed.
     * @param albumIds The ID of an album to unstar. Use this rather than id if the client accesses
     *   the media collection according to ID3 tags rather than file structure. Multiple parameters
     *   allowed.
     * @param artistIds The ID of an artist to unstar. Use this rather than id if the client
     *   accesses the media collection according to ID3 tags rather than file structure. Multiple
     *   parameters allowed.
     */
    suspend fun unstar(
        ids: List<String>? = null,
        albumIds: List<String>? = null,
        artistIds: List<String>? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("unstar"),
        queryParameters = listOf(
            *ids?.map { "id" to it }?.toTypedArray().orEmpty(),
            *albumIds?.map { "albumId" to it }?.toTypedArray().orEmpty(),
            *artistIds?.map { "artistId" to it }?.toTypedArray().orEmpty(),
        )
    ).execute(api).mapResponse { }

    /**
     * Sets the rating for a music file.
     *
     * @since 1.6.0
     * @param id A string which uniquely identifies the file (song) or folder (album/artist) to
     *   rate.
     * @param rating The rating between 1 and 5 (inclusive), or 0 to remove the rating.
     */
    suspend fun setRating(
        id: String,
        rating: Int,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("setRating"),
        queryParameters = listOf(
            "id" to id,
            "rating" to rating,
        )
    ).execute(api).mapResponse { }

    /**
     * Registers the local playback of one or more media files. Typically used when playing media
     * that is cached on the client. This operation includes the following:
     *
     * "Scrobbles" the media files on last.fm if the user has configured his/her last.fm credentials
     * on the Subsonic server (Settings > Personal).
     * Updates the play count and last played timestamp for the media files. (Since 1.11.0)
     * Makes the media files appear in the "Now playing" page in the web app, and appear in the list
     * of songs returned by [getNowPlaying] (Since 1.11.0)
     * Since 1.8.0 you may specify multiple id (and optionally time) parameters to scrobble multiple
     * files.
     *
     * @since 1.5.0
     * @param ids A string which uniquely identifies the file to scrobble.
     * @param times (Since 1.8.0) The time (in milliseconds since 1 Jan 1970) at which the song was
     *   listened to.
     * @param submission Whether this is a "submission" or a "now playing" notification.
     */
    suspend fun scrobble(
        ids: List<String>,
        times: List<Long>? = null,
        submission: Boolean? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("scrobble"),
        queryParameters = listOf(
            *ids.map { "id" to it }.toTypedArray(),
            *times?.map { "time" to it }?.toTypedArray().orEmpty(),
            "submission" to submission,
        )
    ).execute(api).mapResponse { }

    /**
     * Returns information about shared media this user is allowed to manage. Takes no extra
     * parameters.
     *
     * @since 1.6.0
     */
    suspend fun getShares() = ApiRequest.get<ResponseRoot>(
        listOf("getShares"),
    ).execute(api).mapResponse(SubsonicResponse::shares)

    /**
     * Creates a public URL that can be used by anyone to stream music or video from the Subsonic
     * server. The URL is short and suitable for posting on Facebook, Twitter etc. Note: The user
     * must be authorized to share (see Settings > Users > User is allowed to share files with
     * anyone).
     *
     * @since 1.6.0
     * @param id ID of a song, album or video to share. Use one id parameter for each entry to
     *   share.
     * @param description A user-defined description that will be displayed to people visiting the
     *   shared media.
     * @param expires The time at which the share expires. Given as milliseconds since 1970.
     */
    suspend fun createShare(
        id: String,
        description: String? = null,
        expires: Long? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("createShare"),
        queryParameters = listOf(
            "id" to id,
            "description" to description,
            "expires" to expires,
        )
    ).execute(api).mapResponse(SubsonicResponse::shares)

    /**
     * Updates the description and/or expiration date for an existing share.
     *
     * @since 1.6.0
     * @param id ID of the share to update.
     * @param description A user-defined description that will be displayed to people visiting the
     *   shared media.
     * @param expires The time at which the share expires. Given as milliseconds since 1970, or zero
     *   to remove the expiration.
     */
    suspend fun updateShare(
        id: String,
        description: String? = null,
        expires: Long? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("updateShare"),
        queryParameters = listOf(
            "id" to id,
            "description" to description,
            "expires" to expires,
        )
    ).execute(api).mapResponse { }

    /**
     * Deletes an existing share.
     *
     * @since 1.6.0
     * @param id ID of the share to delete.
     */
    suspend fun deleteShare(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("deleteShare"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse { }

    /**
     * Returns all Podcast channels the server subscribes to, and (optionally) their episodes. This
     *   method can also be used to return details for only one channel - refer to the id parameter.
     *   A typical use case for this method would be to first retrieve all channels without
     *   episodes, and then retrieve all episodes for the single channel the user selects.
     *
     * @since 1.6.0
     * @param includeEpisodes (Since 1.9.0) Whether to include Podcast episodes in the returned
     *   result.
     * @param id (Since 1.9.0) If specified, only return the Podcast channel with this ID.
     */
    suspend fun getPodcasts(
        includeEpisodes: Boolean? = null,
        id: String? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getPodcasts"),
        queryParameters = listOf(
            "includeEpisodes" to includeEpisodes,
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::podcasts)

    /**
     * Returns the most recently published Podcast episodes.
     *
     * @since 1.13.0
     * @param count The maximum number of episodes to return.
     */
    suspend fun getNewestPodcasts(
        count: Int? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getNewestPodcasts"),
        queryParameters = listOf(
            "count" to count,
        )
    ).execute(api).mapResponse(SubsonicResponse::newestPodcasts)

    /**
     * Requests the server to check for new Podcast episodes. Note: The user must be authorized for
     * Podcast administration (see Settings > Users > User is allowed to administrate Podcasts).
     *
     * @since 1.9.0
     */
    suspend fun refreshPodcasts() = ApiRequest.get<ResponseRoot>(
        listOf("refreshPodcasts"),
    ).execute(api).mapResponse { }

    /**
     * Adds a new Podcast channel. Note: The user must be authorized for Podcast administration (see
     * Settings > Users > User is allowed to administrate Podcasts).
     *
     * @since 1.9.0
     * @param url The URL of the Podcast to add.
     */
    suspend fun createPodcastChannel(
        url: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("createPodcastChannel"),
        queryParameters = listOf(
            "url" to url,
        )
    ).execute(api).mapResponse { }

    /**
     * Deletes a Podcast channel. Note: The user must be authorized for Podcast administration (see
     * Settings > Users > User is allowed to administrate Podcasts).
     *
     * @since 1.9.0
     * @param id The ID of the Podcast channel to delete.
     */
    suspend fun deletePodcastChannel(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("deletePodcastChannel"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse { }

    /**
     * Deletes a Podcast episode. Note: The user must be authorized for Podcast administration (see
     * Settings > Users > User is allowed to administrate Podcasts).
     *
     * @since 1.9.0
     * @param id The ID of the Podcast episode to delete.
     */
    suspend fun deletePodcastEpisode(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("deletePodcastEpisode"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse { }

    /**
     * Request the server to start downloading a given Podcast episode. Note: The user must be
     * authorized for Podcast administration (see Settings > Users > User is allowed to administrate
     * Podcasts).
     *
     * @since 1.9.0
     * @param id The ID of the Podcast episode to download.
     */
    suspend fun downloadPodcastEpisode(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("downloadPodcastEpisode"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse { }

    /**
     * Controls the jukebox, i.e., playback directly on the server's audio hardware. Note: The user
     * must be authorized to control the jukebox (see Settings > Users > User is allowed to play
     * files in jukebox mode).
     *
     * @since 1.2.0
     * @param action The operation to perform. Must be one of: `get`, `status` (since 1.7.0), `set`
     *   (since 1.7.0), `start`, `stop`, `skip`, `add`, `clear`, `remove`, `shuffle`, `setGain`
     * @param index Used by `skip` and `remove`. Zero-based index of the song to skip to or remove.
     * @param offset (Since 1.7.0) Used by `skip`. Start playing this many seconds into the track.
     * @param ids Used by `add` and `set`. ID of song to add to the jukebox playlist. Use multiple id
     *   parameters to add many songs in the same request. (set is similar to a clear followed by a
     *   add, but will not change the currently playing track.)
     * @param gain Used by `setGain` to control the playback volume. A float value between 0.0 and
     *   1.0.
     */
    suspend fun jukeboxControl(
        action: String,
        index: Int? = null,
        offset: Int? = null,
        ids: List<String>? = null,
        gain: Float? = null,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("jukeboxControl"),
        queryParameters = listOf(
            "action" to action,
            "index" to index,
            "offset" to offset,
            *ids?.map { "id" to it }?.toTypedArray().orEmpty(),
            "gain" to gain,
        )
    ).execute(api).mapResponse { }

    /**
     * Returns all internet radio stations. Takes no extra parameters.
     *
     * @since 1.9.0
     */
    suspend fun getInternetRadioStations() = ApiRequest.get<ResponseRoot>(
        listOf("getInternetRadioStations"),
    ).execute(api).mapResponse(SubsonicResponse::internetRadioStations)

    // TODO

    // OpenSubsonic extensions

    /**
     * Retrieves all structured lyrics from the server for a given song. The lyrics can come from
     * embedded tags (SYLT/USLT), LRC file/text file, or any other external source.
     *
     * Note: OpenSubsonic only.
     *
     * @since OpenSubsonic version 1
     * @param id The track ID
     */
    suspend fun getLyricsBySongId(
        id: String,
    ) = ApiRequest.get<ResponseRoot>(
        listOf("getLyricsBySongId"),
        queryParameters = listOf(
            "id" to id,
        )
    ).execute(api).mapResponse(SubsonicResponse::lyricsList)

    // TODO

    private fun SubsonicError.Code.toError() = when (this) {
        SubsonicError.Code.GENERIC_ERROR -> Error.IO
        SubsonicError.Code.REQUIRED_PARAMETER_MISSING -> Error.IO
        SubsonicError.Code.OUTDATED_CLIENT -> Error.IO
        SubsonicError.Code.OUTDATED_SERVER -> Error.IO
        SubsonicError.Code.WRONG_CREDENTIALS -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.TOKEN_AUTHENTICATION_NOT_SUPPORTED -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.AUTHENTICATION_MECHANISM_NOT_SUPPORTED -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.MULTIPLE_CONFLICTING_AUTHENTICATION_MECHANISMS -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.INVALID_API_KEY -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.USER_NOT_AUTHORIZED -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.SUBSONIC_PREMIUM_TRIAL_ENDED -> Error.INVALID_CREDENTIALS
        SubsonicError.Code.NOT_FOUND -> Error.NOT_FOUND
    }

    private inline fun <T : Any> Result<ResponseRoot, ApiError>.mapResponse(
        methodValue: SubsonicResponse.() -> T?,
    ): Result<T, Error> = when (this) {
        is Result.Success -> {
            when (data.subsonicResponse.status) {
                ResponseStatus.OK -> runCatching {
                    data.subsonicResponse.methodValue()!!
                }.fold(
                    onSuccess = { Result.Success(it) },
                    onFailure = { Result.Failure(Error.INVALID_RESPONSE) }
                )

                ResponseStatus.FAILED -> Result.Failure(
                    data.subsonicResponse.error?.code?.toError() ?: Error.INVALID_RESPONSE
                )
            }
        }

        is Result.Failure -> Result.Failure(error.toError(), throwable)
    }

    companion object {
        private val SUBSONIC_API_VERSION = Version(1, 16, 1)
    }
}
