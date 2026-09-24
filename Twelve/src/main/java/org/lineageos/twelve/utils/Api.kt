/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Result
import java.net.SocketTimeoutException
import kotlin.reflect.KType
import kotlin.reflect.typeOf

typealias MethodResult<T> = Result<T, ApiError>

/**
 * Base interface for all API requests.
 *
 * @param T The return type
 */
interface ApiRequestInterface<T> {
    /**
     * The [KType] of [T], used for serialization.
     */
    val type: KType

    /**
     * Execute the request.
     *
     * @param api The [Api] to use for building and executing this request
     */
    suspend fun execute(api: Api): MethodResult<T>
}

/**
 * Base class for common request functionality.
 */
abstract class BaseRequest {
    protected fun <T> encodeRequestBody(api: Api, data: T?, serializer: KSerializer<T>) =
        data?.let {
            api.json.encodeToString(serializer, it)
        }?.toRequestBody("application/json".toMediaType()) ?: "".toRequestBody()
}

/**
 * GET request implementation.
 *
 * @param T The return type
 */
class GetRequestInterface<T>(
    private val path: List<String>,
    override val type: KType,
    private val queryParameters: List<Pair<String, Any?>> = emptyList()
) : BaseRequest(), ApiRequestInterface<T> {
    override suspend fun execute(api: Api): MethodResult<T> {
        val url = api.buildUrl(path, queryParameters)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return api.executeRequest(request, type)
    }
}

/**
 * POST request implementation.
 *
 * @param D The data type
 * @param T The return type
 */
class PostRequestInterface<D, T>(
    private val path: List<String>,
    override val type: KType,
    private val data: D?,
    private val dataSerializer: KSerializer<D>,
    private val queryParameters: List<Pair<String, Any?>> = emptyList(),
    private val emptyResponse: () -> T
) : BaseRequest(), ApiRequestInterface<T> {
    override suspend fun execute(api: Api): MethodResult<T> {
        val url = api.buildUrl(path, queryParameters)
        val body = encodeRequestBody(api, data, dataSerializer)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        return api.executeRequest(request, type, emptyResponse)
    }
}

/**
 * DELETE request implementation.
 *
 * @param T The return type
 */
class DeleteRequestInterface<T>(
    private val path: List<String>,
    override val type: KType,
    private val queryParameters: List<Pair<String, Any?>> = emptyList(),
    private val emptyResponse: () -> T,
) : BaseRequest(), ApiRequestInterface<T> {
    override suspend fun execute(api: Api): MethodResult<T> {
        val url = api.buildUrl(path, queryParameters)
        val request = Request.Builder()
            .url(url)
            .delete()
            .build()
        return api.executeRequest(request, type, emptyResponse)
    }
}

class Api(
    private val okHttpClient: OkHttpClient,
    private val serverUri: Uri,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    fun buildUrl(
        path: List<String>,
        queryParameters: List<Pair<String, Any?>> = emptyList()
    ) = serverUri.buildUpon().apply {
        path.forEach { appendPath(it) }
        queryParameters.forEach { (key, value) ->
            value?.let { appendQueryParameter(key, it.toString()) }
        }
    }.build().toString()

    suspend fun <T> executeRequest(
        request: Request,
        type: KType,
        onEmptyResponse: () -> T = {
            throw IllegalStateException("No onEmptyResponse() provided, but response is empty")
        }
    ) = withContext(dispatcher) {
        withRetry(maxAttempts = 3) {
            runCatching {
                okHttpClient.newCall(request).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        response.body?.use { body ->
                            val string = body.string()
                            if (string.isEmpty()) {
                                Result.Success(onEmptyResponse())
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                val serializer =
                                    json.serializersModule.serializer(type) as KSerializer<T>
                                Result.Success(json.decodeFromString(serializer, string))
                            }
                        } ?: Result.Success(onEmptyResponse())
                    } else {
                        Result.Failure(
                            ApiError.HttpError(response.code),
                            Throwable(response.message)
                        )
                    }
                }
            }.fold(
                onSuccess = { it },
                onFailure = { e -> Result.Failure(handleError(e), e) }
            )
        }
    }

    private suspend fun <T> withRetry(
        maxAttempts: Int,
        initialDelay: Long = 100,
        maxDelay: Long = 1000,
        factor: Double = 2.0,
        block: suspend () -> MethodResult<T>
    ): MethodResult<T> {
        var currentDelay = initialDelay
        repeat(maxAttempts - 1) { _ ->
            when (val result = block()) {
                is Result.Success -> return result
                is Result.Failure -> when (result.error) {
                    is ApiError.HttpError -> when (result.error.code) {
                        in 500..599 -> {
                            delay(currentDelay)
                            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                        }

                        else -> return result
                    }

                    else -> return result
                }
            }
        }
        return block()
    }

    private fun handleError(e: Throwable): ApiError = when (e) {
        is SocketTimeoutException -> ApiError.HttpError(408)
        is SerializationException -> ApiError.DeserializationError
        is CancellationException -> ApiError.CancellationError
        else -> ApiError.GenericError
    }
}

object ApiRequest {
    inline fun <reified T> get(
        path: List<String>,
        queryParameters: List<Pair<String, Any?>> = emptyList()
    ) = GetRequestInterface<T>(path, typeOf<T>(), queryParameters)

    inline fun <reified D, reified T> post(
        path: List<String>,
        data: D? = null,
        queryParameters: List<Pair<String, Any?>> = emptyList(),
        noinline emptyResponse: () -> T = { Unit as T }
    ) = PostRequestInterface(
        path, typeOf<T>(), data,
        Json.serializersModule.serializer(), queryParameters, emptyResponse
    )

    inline fun <reified T> delete(
        path: List<String>,
        queryParameters: List<Pair<String, Any?>> = emptyList(),
        noinline emptyResponse: () -> T = { Unit as T },
    ) = DeleteRequestInterface(path, typeOf<T>(), queryParameters, emptyResponse)
}

sealed interface ApiError {
    data class HttpError(val code: Int) : ApiError
    data object GenericError : ApiError
    data object DeserializationError : ApiError
    data object CancellationError : ApiError
    data object InvalidResponse : ApiError
}

fun ApiError.toError() = when (this) {
    is ApiError.HttpError -> when (code) {
        401 -> Error.AUTHENTICATION_REQUIRED
        403 -> Error.INVALID_CREDENTIALS
        404 -> Error.NOT_FOUND
        else -> Error.IO
    }

    is ApiError.GenericError -> Error.IO
    is ApiError.DeserializationError -> Error.DESERIALIZATION
    is ApiError.CancellationError -> Error.CANCELLED
    is ApiError.InvalidResponse -> Error.INVALID_RESPONSE
}

fun <T> MethodResult<T>.mapToError() = when (this) {
    is Result.Success -> Result.Success(data)
    is Result.Failure -> Result.Failure(error.toError(), throwable)
}
