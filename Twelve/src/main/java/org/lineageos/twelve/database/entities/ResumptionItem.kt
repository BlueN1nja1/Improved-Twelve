/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Resumption item.
 *
 * @param playlistIndex Index of this item in the playlist
 * @param resumptionPlaylistId ID of the resumption playlist, this is only needed to easily build a
 *   [ResumptionPlaylistWithMediaItems]
 * @param mediaId ID of the media item
 */
@Entity(
    indices = [
        Index(value = ["resumption_playlist_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ResumptionPlaylist::class,
            parentColumns = ["resumption_id"],
            childColumns = ["resumption_playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ResumptionItem(
    @PrimaryKey @ColumnInfo(name = "playlist_index") val playlistIndex: Long,
    @ColumnInfo(name = "resumption_playlist_id") val resumptionPlaylistId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
)
