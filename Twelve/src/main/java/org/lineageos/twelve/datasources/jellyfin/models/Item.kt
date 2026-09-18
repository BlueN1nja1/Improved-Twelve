/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:UseSerializers(UUIDSerializer::class)

package org.lineageos.twelve.datasources.jellyfin.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.lineageos.twelve.datasources.jellyfin.serializers.UUIDSerializer
import java.util.UUID

@Serializable
data class Item(
    @SerialName("Id") val id: UUID,
    @SerialName("Name") val name: String? = null,
    @SerialName("ArtistItems") val artistItems: List<ArtistItem>? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("AlbumId") val albumId: UUID? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SourceType") val sourceType: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("GenreItems") val genreItems: List<GenreItem>? = null,
    @SerialName("Type") val type: ItemType? = null,
    @SerialName("UserData") val userData: UserData? = null,
)
