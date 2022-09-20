/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.plugins.cache

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.core.*
import kotlin.collections.Map
import kotlin.collections.emptyMap
import kotlin.collections.firstOrNull
import kotlin.collections.mutableMapOf
import kotlin.collections.set

@OptIn(InternalAPI::class)
internal suspend fun HttpCacheEntry(response: HttpResponse): HttpCacheEntry {
    val body = response.content.readRemaining().readBytes()
    response.complete()
    return HttpCacheEntry(response.cacheExpires(), response.varyKeys(), response, body)
}

/**
 * Client single response cache with [expires] and [varyKeys].
 */
public class HttpCacheEntry internal constructor(
    public val expires: GMTDate,
    public val varyKeys: Map<String, String>,
    public val response: HttpResponse,
    public val body: ByteArray
) {
    internal val responseHeaders: Headers = Headers.build {
        appendAll(response.headers)
    }

    internal fun produceResponse(): HttpResponse {
        val currentClient = response.call.client
        val call = SavedHttpCall(currentClient, response.call.request, response, body)
        return call.response
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is HttpCacheEntry) return false
        if (other === this) return true
        return varyKeys == other.varyKeys
    }

    override fun hashCode(): Int {
        return varyKeys.hashCode()
    }
}

internal fun HttpResponse.varyKeys(): Map<String, String> {
    val validationKeys = vary() ?: return emptyMap()

    val result = mutableMapOf<String, String>()
    val requestHeaders = call.request.headers

    for (key in validationKeys) {
        result[key] = requestHeaders[key] ?: ""
    }

    return result
}

internal fun HttpResponse.cacheExpires(fallback: () -> GMTDate = { GMTDate() }): GMTDate {
    val cacheControl = cacheControl()

    val isPrivate = CacheControl.PRIVATE in cacheControl

    val maxAgeKey = if (isPrivate) "s-max-age" else "max-age"

    val maxAge = cacheControl.firstOrNull { it.value.startsWith(maxAgeKey) }
        ?.value?.split("=")
        ?.get(1)?.toInt()

    if (maxAge != null) {
        return call.response.requestTime + maxAge * 1000L
    }

    val expires = headers[HttpHeaders.Expires]
    return expires?.let {
        // Handle "0" case faster
        if (it == "0" || it.isBlank()) return fallback()

        return try {
            it.fromHttpToGmtDate()
        } catch (e: Throwable) {
            fallback()
        }
    } ?: fallback()
}

internal fun HttpCacheEntry.shouldValidate(request: HttpRequestBuilder): ValidateStatus {
    val cacheControl = parseHeaderValue(responseHeaders[HttpHeaders.CacheControl])
    val requestCacheControl = parseHeaderValue(request.headers[HttpHeaders.CacheControl])

    if (CacheControl.NO_CACHE in requestCacheControl) return ValidateStatus.ShouldValidate

    val requestMaxAge = requestCacheControl.firstOrNull { it.value.startsWith("max-age=") }
        ?.value?.split("=")
        ?.get(1)?.let { it.toIntOrNull() ?: 0 }
    if (requestMaxAge == 0) return ValidateStatus.ShouldValidate

    val validMillis = expires.timestamp - getTimeMillis()

    if (CacheControl.NO_CACHE in cacheControl) return ValidateStatus.ShouldValidate
    if (validMillis > 0) return ValidateStatus.ShouldNotValidate
    if (CacheControl.MUST_REVALIDATE in cacheControl) return ValidateStatus.ShouldValidate

    val maxStale = requestCacheControl.firstOrNull { it.value.startsWith("max-stale=") }
        ?.value?.substring("max-stale=".length)
        ?.toIntOrNull() ?: 0
    val maxStaleMillis = maxStale * 1000L
    if (validMillis + maxStaleMillis > 0) return ValidateStatus.ShouldWarn
    return ValidateStatus.ShouldValidate
}

internal enum class ValidateStatus {
    ShouldValidate, ShouldNotValidate, ShouldWarn
}
