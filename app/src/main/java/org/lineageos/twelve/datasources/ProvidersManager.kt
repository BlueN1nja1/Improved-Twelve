/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.lineageos.twelve.ext.mapAsync
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Provider
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.repositories.ProvidersRepository

/**
 * Helper class for managing [MediaDataSource] instances.
 *
 * @param T Provider instance type
 * @param coroutineScope [CoroutineScope]
 * @param providersRepository [ProvidersRepository]
 * @param providerType The [ProviderType] of the data source providers
 * @param providerToArgumentsMapper A function that maps a [Provider] to [T]
 */
class ProvidersManager<T : ProvidersManager.Instance>(
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository,
    providerType: ProviderType,
    providerToArgumentsMapper: suspend (Provider, Bundle) -> T,
) {
    /**
     * A data source instance. Ideally each provider has a dedicated instance.
     */
    interface Instance {
        /**
         * Check if this instance handles this media item. Be sure to also handle URIs that aren't
         * compatible with this data source.
         *
         * @param mediaItemUri Media item URI
         * @return True if this instance handles this media item, false otherwise
         */
        suspend fun isMediaItemCompatible(mediaItemUri: Uri): Boolean
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val providersToArguments = providersRepository.allProvidersToArguments
        .mapLatest { allProviders -> allProviders.filter { it.first.type == providerType } }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val providers = providersToArguments
        .mapLatest { providersToArguments ->
            providersToArguments.map { (provider, _) -> provider }
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val providersToInstance = providersToArguments
        .mapLatest { providersToArguments ->
            providersToArguments.mapAsync { (provider, arguments) ->
                provider to providerToArgumentsMapper(provider, arguments)
            }.toMap()
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val providerIdsToInstance = providersToInstance
        .mapLatest { providersToInstance ->
            providersToInstance.map { (provider, instance) ->
                provider.typeId to instance
            }.toMap()
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = mapOf(),
        )

    /**
     * @see MediaDataSource.providerOf
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun providerOf(
        mediaItemUri: Uri,
    ) = providersToInstance.mapLatest { providersToInstance ->
        providersToInstance.firstNotNullOfOrNull { (provider, instance) ->
            provider.takeIf { instance.isMediaItemCompatible(mediaItemUri) }
        }?.let {
            Result.Success(it.identifier)
        } ?: Result.Failure(Error.NOT_FOUND)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> flatMapWithInstanceOf(
        providerIdentifier: ProviderIdentifier,
        block: suspend T.() -> Flow<Result<R, Error>>,
    ) = providerIdsToInstance.flatMapLatest { providerIdsToType ->
        providerIdsToType[providerIdentifier.typeId]?.block() ?: flowOf(
            Result.Failure(Error.NOT_FOUND)
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> flatMapWithInstanceOf(
        vararg mediaItemUris: Uri,
        block: suspend T.() -> Flow<Result<R, Error>>,
    ) = providerIdsToInstance.flatMapLatest { providerIdsToType ->
        providerIdsToType.firstNotNullOfOrNull { (_, instance) ->
            instance.takeIf {
                mediaItemUris.all { mediaItemUri ->
                    it.isMediaItemCompatible(mediaItemUri)
                }
            }
        }?.block() ?: flowOf(Result.Failure(Error.NOT_FOUND))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> mapWithInstanceOf(
        providerIdentifier: ProviderIdentifier,
        block: suspend T.() -> Result<R, Error>,
    ) = providerIdsToInstance.mapLatest { providerIdsToType ->
        providerIdsToType[providerIdentifier.typeId]?.block() ?: Result.Failure(Error.NOT_FOUND)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <R> mapWithInstanceOf(
        vararg mediaItemUris: Uri,
        block: suspend T.() -> Result<R, Error>,
    ) = providerIdsToInstance.mapLatest { providerIdsToType ->
        providerIdsToType.firstNotNullOfOrNull { (_, instance) ->
            instance.takeIf {
                mediaItemUris.all { mediaItemUri ->
                    it.isMediaItemCompatible(mediaItemUri)
                }
            }
        }?.block() ?: Result.Failure(Error.NOT_FOUND)
    }

    suspend fun <R> doWithInstanceOf(
        providerIdentifier: ProviderIdentifier,
        block: suspend T.() -> Result<R, Error>,
    ) = providerIdsToInstance.value[providerIdentifier.typeId]?.block()
        ?: Result.Failure(Error.NOT_FOUND)

    suspend fun <R> doWithInstanceOf(
        vararg mediaItemUris: Uri,
        block: suspend T.() -> Result<R, Error>,
    ) = providerIdsToInstance.value.firstNotNullOfOrNull { (_, instance) ->
        instance.takeIf {
            mediaItemUris.all { mediaItemUri ->
                it.isMediaItemCompatible(mediaItemUri)
            }
        }
    }?.block() ?: Result.Failure(Error.NOT_FOUND)
}
