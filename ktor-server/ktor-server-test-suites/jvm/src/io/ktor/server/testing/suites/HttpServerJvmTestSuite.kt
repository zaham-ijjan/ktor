/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.testing.suites

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.*
import java.net.*
import java.nio.*
import kotlin.coroutines.*
import kotlin.test.*
import kotlin.text.toByteArray
import kotlin.time.Duration.Companion.seconds

abstract class HttpServerJvmTestSuite<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(
    hostFactory: ApplicationEngineFactory<TEngine, TConfiguration>
) : EngineTestBase<TEngine, TConfiguration>(hostFactory) {

    @Test
    fun testRequestTwiceInOneBufferWithKeepAlive() {
        createAndStartServer {
            get("/") {
                val d = call.request.queryParameters["d"]!!.toLong()
                delay(d.seconds.inWholeMilliseconds)

                call.response.header("D", d.toString())
                call.respondText("Response for $d\n")
            }
        }

        val s = Socket()
        s.tcpNoDelay = true

        val impudent = buildString {
            append("GET /?d=2 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")

            append("GET /?d=1 HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()

        s.connect(InetSocketAddress(port))
        s.use { _ ->
            s.getOutputStream().apply {
                write(impudent)
                flush()
            }

            val responses = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).lineSequence()
                .filterNot { line ->
                    line.startsWith("Date") || line.startsWith("Server") ||
                        line.startsWith("Content-") || line.toIntOrNull() != null ||
                        line.isBlank() || line.startsWith("Connection") || line.startsWith("Keep-Alive")
                }
                .map { it.trim() }
                .joinToString(separator = "\n").replace("200 OK", "200")

            assertEquals(
                """
                HTTP/1.1 200
                D: 2
                Response for 2
                HTTP/1.1 200
                D: 1
                Response for 1
                """.trimIndent().replace(
                    "\r\n",
                    "\n"
                ),
                responses
            )
        }
    }

    @Test
    fun testClosedConnection() {
        val completed = Job()

        createAndStartServer {
            get("/file") {
                try {
                    call.respond(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                val bb = ByteBuffer.allocate(512)
                                for (i in 1L..1000L) {
                                    delay(100)
                                    bb.clear()
                                    while (bb.hasRemaining()) {
                                        bb.putLong(i)
                                    }
                                    bb.flip()
                                    channel.writeFully(bb)
                                    channel.flush()
                                }

                                channel.close()
                            }
                        }
                    )
                } finally {
                    completed.cancel()
                }
            }
        }

        socket {
            outputStream.writePacket(
                RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/file", "HTTP/1.1")
                    headerLine("Host", "localhost:$port")
                    headerLine("Connection", "keep-alive")
                    emptyLine()
                }.build()
            )

            outputStream.flush()

            inputStream.read(ByteArray(100))
        } // send FIN

        runBlocking {
            withTimeout(5000L) {
                completed.join()
            }
        }
    }

    @Test
    fun testConnectionReset() {
        val completed = Job()

        createAndStartServer {
            get("/file") {
                try {
                    call.respond(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                val bb = ByteBuffer.allocate(512)
                                for (i in 1L..1000L) {
                                    delay(100)
                                    bb.clear()
                                    while (bb.hasRemaining()) {
                                        bb.putLong(i)
                                    }
                                    bb.flip()
                                    channel.writeFully(bb)
                                    channel.flush()
                                }

                                channel.close()
                            }
                        }
                    )
                } finally {
                    completed.cancel()
                }
            }
        }

        socket {
            // to ensure immediate RST at close it is very important to set SO_LINGER = 0
            setSoLinger(true, 0)

            outputStream.writePacket(
                RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/file", "HTTP/1.1")
                    headerLine("Host", "localhost:$port")
                    headerLine("Connection", "keep-alive")
                    emptyLine()
                }.build()
            )

            outputStream.flush()

            inputStream.read(ByteArray(100))
        } // send FIN + RST

        runBlocking {
            withTimeout(5000L) {
                completed.join()
            }
        }
    }

    @Test
    open fun testUpgrade() {
        val completed = CompletableDeferred<Unit>()

        createAndStartServer {
            get("/up") {
                call.respond(
                    object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers
                            get() = Headers.build {
                                append(HttpHeaders.Upgrade, "up")
                                append(HttpHeaders.Connection, "Upgrade")
                            }

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                try {
                                    println("START TEST UPGRADE")
                                    val bb = ByteBuffer.allocate(8)
                                    println("TEST UPGRADE - 1")
                                    // here
                                    input.readFully(bb)
                                    println("TEST UPGRADE - 2")
                                    bb.flip()

                                    println("TEST UPGRADE - START WRITE OUT")
                                    output.writeFully(bb)
                                    println("TEST UPGRADE - PREPARE CLOSE")
                                    output.close()

                                    println("TEST UPGRADE - CLOSED")
                                    input.readRemaining().use {
                                        assertEquals(0, it.remaining)
                                    }
                                    completed.complete(Unit)
                                    println("END TEST UPGRADE")
                                } catch (t: Throwable) {
                                    println("TEST UPGRADE - EXCEPTION")
                                    completed.completeExceptionally(t)
                                    throw t
                                }
                            }
                        }
                    }
                )
            }
        }

        socket {
            outputStream.apply {
                val p = RequestResponseBuilder().apply {
                    requestLine(HttpMethod.Get, "/up", "HTTP/1.1")
                    headerLine(HttpHeaders.Host, "localhost:$port")
                    headerLine(HttpHeaders.Upgrade, "up")
                    headerLine(HttpHeaders.Connection, "upgrade")
                    emptyLine()
                }.build()
                writePacket(p)
                flush()
            }

            val ch = ByteChannel(true)

            runBlocking {
                launch(coroutineContext) {
                    val s = inputStream
                    val bytes = ByteArray(512)
                    try {
                        println("START TEST SOCKET READING")
                        while (true) {
                            if (s.available() > 0) {
                                val rc = s.read(bytes)
                                println("TEST SOCKET READING - 1")
                                ch.writeFully(bytes, 0, rc)
                                println("TEST SOCKET READING - 2")
                            } else {
                                yield()
                                println("TEST SOCKET READING - 3")
                                val rc = s.read(bytes)
                                println("TEST SOCKET READING - 4")
                                if (rc == -1) break
                                println("TEST SOCKET READING - 5")
                                ch.writeFully(bytes, 0, rc)
                                println("TEST SOCKET READING - 6")
                            }

                            yield()
                        }
                        println("END TEST SOCKET READING")
                    } catch (t: Throwable) {
                        ch.close(t)
                    } finally {
                        ch.close()
                    }
                }

                println("TEST TRY TO GET RESPONSE")
                val response = parseResponse(ch)!!
                println("TEST GOT RESPONSE")

                assertEquals(HttpStatusCode.SwitchingProtocols.value, response.status)
                assertEquals("Upgrade", response.headers[HttpHeaders.Connection]?.toString())
                assertEquals("up", response.headers[HttpHeaders.Upgrade]?.toString())

                (0 until response.headers.size)
                    .map { response.headers.nameAt(it).toString() }
                    .groupBy { it }.forEach { (name, values) ->
                        assertEquals(1, values.size, "Duplicate header $name")
                    }

                outputStream.apply {
                    writePacket {
                        writeLong(0x1122334455667788L)
                    }
                    flush()
                }

                assertEquals(0x1122334455667788L, ch.readLong())

                close()

                completed.await()
            }
        }
    }
}
