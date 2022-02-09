/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.server.netty

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

fun main() {
    GlobalScope.launch {
        delay(5000)
        val client = HttpClient() {
            engine {
                threadsCount = 8
                pipelining = true
            }
        }
        while(true) {
            client.get("http://127.0.0.1:8080/")
        }
    }
    embeddedServer(Netty, port = 8080) {
        routing {
            get("/") {
                call.respondText("Hello")
            }
        }
    }.start(wait = true)
}
