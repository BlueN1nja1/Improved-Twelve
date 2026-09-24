/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.lineageos.twelve.database.converters.InstantConverter
import org.lineageos.twelve.database.converters.UriConverter
import org.lineageos.twelve.database.dao.AmpacheProviderDao
import org.lineageos.twelve.database.dao.FavoriteDao
import org.lineageos.twelve.database.dao.JellyfinProviderDao
import org.lineageos.twelve.database.dao.MediaStatsDao
import org.lineageos.twelve.database.dao.PlaylistDao
import org.lineageos.twelve.database.dao.PlaylistItemCrossRefDao
import org.lineageos.twelve.database.dao.PlaylistWithItemsDao
import org.lineageos.twelve.database.dao.ResumptionPlaylistDao
import org.lineageos.twelve.database.dao.SubsonicProviderDao
import org.lineageos.twelve.database.entities.AmpacheProvider
import org.lineageos.twelve.database.entities.Favorite
import org.lineageos.twelve.database.entities.JellyfinProvider
import org.lineageos.twelve.database.entities.LocalMediaStats
import org.lineageos.twelve.database.entities.Playlist
import org.lineageos.twelve.database.entities.PlaylistItemCrossRef
import org.lineageos.twelve.database.entities.ResumptionItem
import org.lineageos.twelve.database.entities.ResumptionPlaylist
import org.lineageos.twelve.database.entities.SubsonicProvider

@Database(
    entities = [
        /* Favorites */
        Favorite::class,

        /* Playlist */
        Playlist::class,
        PlaylistItemCrossRef::class,

        /* Resumption */
        ResumptionItem::class,
        ResumptionPlaylist::class,

        /* Providers */
        AmpacheProvider::class,
        JellyfinProvider::class,
        SubsonicProvider::class,

        /* Local Media Stats */
        LocalMediaStats::class,
    ],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = TwelveDatabase.Companion.MigrationSpec6To7::class),
        // 7 to 8 is done manually
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, spec = TwelveDatabase.Companion.MigrationSpec9To10::class),
    ],
)
@TypeConverters(
    InstantConverter::class,
    UriConverter::class,
)
abstract class TwelveDatabase : RoomDatabase() {
    abstract fun getAmpacheProviderDao(): AmpacheProviderDao
    abstract fun getFavoriteDao(): FavoriteDao
    abstract fun getJellyfinProviderDao(): JellyfinProviderDao
    abstract fun getLocalMediaStatsProviderDao(): MediaStatsDao
    abstract fun getPlaylistDao(): PlaylistDao
    abstract fun getPlaylistItemCrossRefDao(): PlaylistItemCrossRefDao
    abstract fun getPlaylistWithItemsDao(): PlaylistWithItemsDao
    abstract fun getResumptionPlaylistDao(): ResumptionPlaylistDao
    abstract fun getSubsonicProviderDao(): SubsonicProviderDao

    companion object {
        @DeleteColumn.Entries(
            DeleteColumn(
                tableName = "Item",
                columnName = "count"
            ),
            DeleteColumn(
                tableName = "LocalMediaStats",
                columnName = "favorite"
            ),
            DeleteColumn(
                tableName = "Playlist",
                columnName = "track_count"
            ),
        )
        @DeleteTable.Entries(
            DeleteTable(
                tableName = "LastPlayed"
            ),
        )
        @RenameColumn.Entries(
            RenameColumn(
                tableName = "LocalMediaStats",
                fromColumnName = "media_uri",
                toColumnName = "audio_uri"
            ),
            RenameColumn(
                tableName = "Playlist",
                fromColumnName = "last_modified",
                toColumnName = "created_at"
            ),
        )
        class MigrationSpec6To7 : AutoMigrationSpec

        object Migration7To8 : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Favorite: Start
                // Create temp table (copy paste query from JSON, reorder entries to match
                // auto-generated migration)
                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS `Favorite_temp`
                        (
                            `added_at` TEXT NOT NULL,
                            `audio_uri` TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY(`audio_uri`)
                        )
                    """.trimIndent()
                )
                // Migrate data
                db.execSQL(
                    """
                        INSERT INTO Favorite_temp (audio_uri, added_at)
                        SELECT audio_uri, added_at FROM Favorite
                            INNER JOIN Item ON Item.item_id = Favorite.item_id
                    """.trimIndent()
                )
                // Delete old table and rename new table
                db.execSQL(
                    """
                        DROP TABLE IF EXISTS `Favorite`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                        ALTER TABLE `Favorite_temp`
                        RENAME TO `Favorite`
                    """.trimIndent()
                )
                // Create indexes
                db.execSQL(
                    """
                        CREATE UNIQUE INDEX IF NOT EXISTS `index_Favorite_audio_uri`
                        ON `Favorite` (`audio_uri`)
                    """.trimIndent()
                )
                // Favorite: End

                // PlaylistItemCrossRef: Start
                // Create temp table (copy paste query from JSON, reorder entries to match
                // auto-generated migration)
                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS `PlaylistItemCrossRef_temp`
                        (
                            `last_modified` INTEGER NOT NULL,
                            `playlist_id` INTEGER NOT NULL,
                            `audio_uri` TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY(`playlist_id`, `audio_uri`),
                            FOREIGN KEY(`playlist_id`) REFERENCES `Playlist`(`playlist_id`)
                                ON UPDATE CASCADE
                                ON DELETE CASCADE
                        )
                    """.trimIndent()
                )
                // Migrate data
                db.execSQL(
                    """
                        INSERT INTO PlaylistItemCrossRef_temp (playlist_id, audio_uri, last_modified)
                        SELECT playlist_id, audio_uri, last_modified FROM PlaylistItemCrossRef
                            INNER JOIN Item ON Item.item_id = PlaylistItemCrossRef.item_id
                    """.trimIndent()
                )
                // Delete old table and rename new table
                db.execSQL(
                    """
                        DROP TABLE IF EXISTS `PlaylistItemCrossRef`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                        ALTER TABLE `PlaylistItemCrossRef_temp`
                        RENAME TO `PlaylistItemCrossRef`
                    """.trimIndent()
                )
                // Create indexes
                db.execSQL(
                    """
                        CREATE INDEX IF NOT EXISTS `index_PlaylistItemCrossRef_playlist_id`
                        ON `PlaylistItemCrossRef` (`playlist_id`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                        CREATE INDEX IF NOT EXISTS `index_PlaylistItemCrossRef_audio_uri`
                        ON `PlaylistItemCrossRef` (`audio_uri`)
                    """.trimIndent()
                )
                // PlaylistItemCrossRef: End

                // Item: Start
                // Delete the table
                db.execSQL(
                    """
                        DROP TABLE IF EXISTS `Item`
                    """.trimIndent()
                )
                // Item: End
            }
        }

        @RenameColumn.Entries(
            RenameColumn(
                tableName = "LocalMediaStats",
                fromColumnName = "audio_uri",
                toColumnName = "uri"
            )
        )
        class MigrationSpec9To10 : AutoMigrationSpec

        fun get(context: Context) = Room.databaseBuilder(
            context.applicationContext,
            TwelveDatabase::class.java,
            "twelve_database",
        )
            .addMigrations(Migration7To8)
            .build()
    }
}
