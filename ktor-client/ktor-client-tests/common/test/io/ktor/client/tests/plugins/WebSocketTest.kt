/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests.plugins

import io.ktor.client.plugins.websocket.*
import io.ktor.client.tests.utils.*
import io.ktor.websocket.*
import kotlin.test.*

class WebSocketTest : ClientLoader() {
    private val echoWebsocket = "$TEST_WEBSOCKET_SERVER/websockets/echo"

    private class CustomException : Exception()

    
}
