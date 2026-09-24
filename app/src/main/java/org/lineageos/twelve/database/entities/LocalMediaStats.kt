/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.entities

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity for local media stats
 *
 * @param uri The [Uri] of the audio
 * @param playCount The number of times the media has been played
 */
@Entity
data class LocalMediaStats(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: Uri,
    @ColumnInfo(name = "play_count", defaultValue = "1") val playCount: Long,
)
