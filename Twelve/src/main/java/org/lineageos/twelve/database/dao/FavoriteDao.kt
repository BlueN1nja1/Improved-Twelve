/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.dao

import android.net.Uri
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface FavoriteDao {
    /**
     * Get all the favorite items.
     */
    @Query(
        """
            SELECT audio_uri
            FROM favorite
        """
    )
    fun getAll(): Flow<List<Uri>>

    /**
     * Check whether this item is a favorite.
     */
    @Query(
        """
            SELECT COUNT(*) > 0
            FROM favorite
            WHERE audio_uri = :audioUri
        """
    )
    fun containsFlow(audioUri: Uri): Flow<Boolean>

    /**
     * Add this item to favorites.
     */
    @Query(
        """
            INSERT INTO favorite (audio_uri, added_at)
            VALUES (:audioUri, :addedAt)
        """
    )
    suspend fun add(audioUri: Uri, addedAt: Instant = Instant.now())

    /**
     * Remove this item from favorites.
     */
    @Query(
        """
            DELETE
            FROM favorite
            WHERE audio_uri = :audioUri
        """
    )
    suspend fun remove(audioUri: Uri)
}
