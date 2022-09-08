/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.cleint.plugins.sse

public class SseEvent(
    public val id: String,
    public val event: String,
    public val data: String,
    public val retry: Long? = null
)
