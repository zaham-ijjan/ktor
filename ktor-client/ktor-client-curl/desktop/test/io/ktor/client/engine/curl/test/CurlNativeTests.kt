/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.engine.curl.test

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*
import kotlin.test.*

val backgroundWorker = Worker.start()

class CurlNativeTests {

    @Test
    @Ignore
    fun testDownloadInBackground() {
        backgroundWorker.execute(TransferMode.SAFE, { Unit }) {
            runBlocking {
                HttpClient(Curl).use {
                    it.get("http://google.com").body<String>()
                }
            }
        }.consume { assert(it.isNotEmpty()) }
    }

    @Test
    fun testDownload() = runBlocking {
        HttpClient(Curl).use {
            val res = it.get("http://google.com").body<String>()
            assert(res.isNotEmpty())
        }
    }

    @Test
    fun testDelete(): Unit = runBlocking {
        val server = embeddedServer(CIO, 0) {
            routing {
                delete("/delete") {
                    call.respondText("OK ${call.receiveText()}")
                }
            }
        }.start()
        val port = server.resolvedConnectors().first().port

        HttpClient(Curl).use {
            val response = it.delete("http://localhost:$port/delete")
            assertEquals("OK ", response.bodyAsText())

            val responseWithBody = it.delete("http://localhost:$port/delete") {
                setBody("1")
            }
            assertEquals("OK 1", responseWithBody.bodyAsText())
        }

        server.stop(0, 500)
    }
}
