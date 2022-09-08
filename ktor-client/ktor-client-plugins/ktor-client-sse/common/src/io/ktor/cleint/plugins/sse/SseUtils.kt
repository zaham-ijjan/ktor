/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.cleint.plugins.sse

import io.ktor.utils.io.*

internal suspend fun ByteReadChannel.parseSseEvent(): SseEvent? {
    var event = ""
    var id = ""
    var retry: Long? = null
    val data = StringBuilder()

    fun processKeyPair(name: String, value: String) {
        when (name) {
            "data" -> data.append(value)
            "event" -> event = value
            "id" -> id = value
            "retry" -> retry = value.toLongOrNull()
        }
    }

    fun processValue(value: String) {
        data.append(value.trimEnd('\n'))
    }

    while (true) {
        val line = readUTF8Line() ?: break
        if (line.isBlank()) break

        val colonIndex = line.indexOf(':')
        if (colonIndex >= 0) {
            val name = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            processKeyPair(name, value)
        } else {
            processValue(line)
        }
    }

    return SseEvent(id, event, data.toString(), retry)
}
