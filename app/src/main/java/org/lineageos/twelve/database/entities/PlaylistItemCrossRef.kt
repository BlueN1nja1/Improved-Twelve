/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A many-to-many table to store playlists' songs.
 *
 * @param playlistId The id of the playlist
 * @param audioUri The [Uri] of the audio
 * @param lastModified The last time the item was modified
 */
@Entity(
    primaryKeys = ["playlist_id", "audio_uri"],
    indices = [
        Index(value = ["audio_uri"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ]
)
data class PlaylistItemCrossRef(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "audio_uri", defaultValue = "") val audioUri: Uri,
    @ColumnInfo(name = "last_modified") val lastModified: Long = System.currentTimeMillis(),
)
