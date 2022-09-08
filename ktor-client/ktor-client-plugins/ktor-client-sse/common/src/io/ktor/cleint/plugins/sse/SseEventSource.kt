/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.cleint.plugins.sse

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

public class SseEventSource(
    public val response: HttpResponse
) : Closeable {
    private val _events = MutableSharedFlow<SseEvent>()

    private val parser = response.launch {
        val channel = response.bodyAsChannel()
        try {
            while (true) {
                val event = channel.parseSseEvent() ?: break
                _events.emit(event)
            }
        } finally {
            channel.cancel()
            response.cancel()
        }
    }

    public val events: SharedFlow<SseEvent> = _events

    public fun onMessage(block: suspend (SseEvent) -> Unit): Job = response.launch {
        events.collect {
            block(it)
        }
    }

    public fun addEventListener(name: String, block: suspend (SseEvent) -> Unit): Job = onMessage {
        if (it.event == name) {
            block(it)
        }
    }

    /**
     * Cancel request stream. All collectors will be cancelled as well.
     */
    public override fun close() {
        parser.cancel()
    }
}
