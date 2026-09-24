/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database.dao

import android.net.Uri
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.lineageos.twelve.database.entities.LocalMediaStats

@Dao
interface MediaStatsDao {

    /**
     * Delete an entry.
     */
    @Query("DELETE FROM LocalMediaStats WHERE uri IN (:mediaUris)")
    suspend fun delete(mediaUris: List<Uri>)

    /**
     * Delete all entries.
     */
    @Query("DELETE FROM LocalMediaStats")
    suspend fun deleteAll()

    /**
     * Increase the play count of an entry by 1.
     */
    @Query(
        """
            INSERT
            INTO LocalMediaStats (uri, play_count)
            VALUES (:uri, 1)
            ON CONFLICT(uri) DO UPDATE SET
                play_count = play_count + 1
        """
    )
    suspend fun increasePlayCount(uri: Uri)

    /**
     * Increase the play count of multiple entries by 1.
     */
    @Transaction
    suspend fun increasePlayCount(mediaUris: List<Uri>) {
        mediaUris.distinct().forEach {
            increasePlayCount(it)
        }
    }

    /**
     * Fetch all entries.
     */
    @Query("SELECT * FROM LocalMediaStats")
    suspend fun getAll(): List<LocalMediaStats>

    /**
     * Fetch all entries as a flow.
     */
    @Query("SELECT * FROM LocalMediaStats")
    fun getAllFlow(): Flow<List<LocalMediaStats>>

    /**
     * Fetch all entries sorted by play count.
     */
    @Query(
        """
            SELECT *
            FROM LocalMediaStats
            WHERE uri LIKE :uriPattern
            ORDER BY play_count DESC
            LIMIT :limit
        """
    )
    fun getAllByPlayCount(uriPattern: String, limit: Int): Flow<List<LocalMediaStats>>
}
