/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.cleint.plugins.sse

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*

/**
 * Receives response body as server-sent event stream.
 */
@Suppress("SuspendFunctionOnCoroutineScope")
public suspend fun HttpClient.sse(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit
): SseEventSource {
    val result = CompletableDeferred<SseEventSource>()

    launch {
        try {
            prepareRequest(urlString) {
                header(HttpHeaders.Accept, ContentType.Text.EventStream)
                header(HttpHeaders.CacheControl, "no-cache")
                header(HttpHeaders.AcceptCharset, "UTF-8")

                block()
            }.execute {
                result.complete(SseEventSource(it))
            }
        } catch (cause: Throwable) {
            result.completeExceptionally(cause)
        }
    }

    return result.await()
}

public suspend fun HttpClient.eventStream(
    urlString: String,
    block: HttpRequestBuilder.() -> Unit
): SseEventSource = sse(urlString, block)
