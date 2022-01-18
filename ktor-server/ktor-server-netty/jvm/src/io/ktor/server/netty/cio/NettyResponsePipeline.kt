/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.netty.cio

import io.ktor.http.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.*
import kotlinx.coroutines.*
import java.io.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*

private const val UNFLUSHED_LIMIT = 65536

@OptIn(InternalAPI::class)
internal class NettyResponsePipeline constructor(
    private val context: ChannelHandlerContext,
    override val coroutineContext: CoroutineContext,
    private val responseQueue: Queue<NettyApplicationCall>,
    private val isReadComplete: AtomicBoolean,
) : CoroutineScope {
    private val needsFlush: AtomicBoolean = AtomicBoolean(false)

    private var processingStarted: Boolean = false

    private var prevCall: ChannelPromise = context.newPromise().also {
        it.setSuccess()
    }

    fun markReadingStopped() {
        if (needsFlush.get()) {
            needsFlush.set(false)
            context.flush()
        }
    }

    fun processResponse(call: NettyApplicationCall) {
        responseQueue.add(call)
        if (processingStarted) return
        processingStarted = true
        startResponseProcessing()
    }

    private fun startResponseProcessing() {
        while (true) {
            val call = responseQueue.poll() ?: break

            call.previousCallFinished = prevCall
            call.callFinished = context.newPromise()
            prevCall = call.callFinished

            processElement(call)
        }
        processingStarted = false
    }

    private fun processElement(call: NettyApplicationCall) {
        try {
            call.response.responseFlag.addListener {
                call.previousCallFinished.addListener {
                    processCall(call)
                }
            }
        } catch (actualException: Throwable) {
            processCallFailed(call, actualException)
        } finally {
            call.responseWriteJob.cancel()
        }
    }

    private fun processCallFailed(call: NettyApplicationCall, actualException: Throwable) {
        val t = when {
            actualException is IOException && actualException !is ChannelIOException ->
                ChannelWriteException(exception = actualException)
            else -> actualException
        }

        call.response.responseChannel.cancel(t)
        call.responseWriteJob.cancel()
        call.response.cancel()
        call.dispose()
        call.callFinished.setFailure(t)
    }

    private fun processUpgrade(call: NettyApplicationCall, responseMessage: Any): ChannelFuture {
        val future = context.write(responseMessage)
        call.upgrade(context)
        call.isRaw = true

        context.flush()
        needsFlush.set(false)
        return future
    }

    private fun finishCall(
        call: NettyApplicationCall,
        lastMessage: Any?,
        lastFuture: ChannelFuture
    ) {
        val prepareForClose = !call.request.keepAlive || call.response.isUpgradeResponse()

        val future = if (lastMessage != null) {
            context.write(lastMessage)
        } else {
            null
        }

        val finishLambda = finishLambda@{
            if (prepareForClose) {
                close(call, lastFuture)
                return@finishLambda
            }
            if (responseQueue.isEmpty()) {
                scheduleFlush()
            }
        }

        future?.addListener {
            finishLambda()
        }
        finishLambda()

        if (!prepareForClose) {
            call.callFinished.setSuccess()
        }
    }

    fun close(call: NettyApplicationCall, lastFuture: ChannelFuture) {
        context.flush()
        needsFlush.set(false)
        lastFuture.addListener {
            context.close()
            call.callFinished.setSuccess()
        }
    }

    private fun scheduleFlush() {
        context.executor().execute {
            if (responseQueue.isEmpty() && (needsFlush.get() || isReadComplete.get())) {
                needsFlush.set(false)
                context.flush()
            }
        }
    }

    private fun processCall(call: NettyApplicationCall) {
        val responseMessage = call.response.responseMessage
        val response = call.response

        val requestMessageFuture = if (response.isUpgradeResponse()) {
            processUpgrade(call, responseMessage)
        } else {
            needsFlush.set(true)
            if (isReadComplete.get()) {
                context.writeAndFlush(responseMessage)
            } else {
                context.write(responseMessage)
            }
        }

        if (responseMessage is FullHttpResponse) {
            return finishCall(call, null, requestMessageFuture)
        } else if (responseMessage is Http2HeadersFrame && responseMessage.isEndStream) {
            return finishCall(call, null, requestMessageFuture)
        }

        val responseChannel = response.responseChannel
        val bodySize = when {
            responseChannel === ByteReadChannel.Empty -> 0
            responseMessage is HttpResponse -> responseMessage.headers().getInt("Content-Length", -1)
            responseMessage is Http2HeadersFrame -> responseMessage.headers().getInt("content-length", -1)
            else -> -1
        }

        launch(context.executor().asCoroutineDispatcher()) {
            processResponseBody(
                call,
                response,
                bodySize,
                requestMessageFuture
            )
        }
    }

    private suspend fun processResponseBody(
        call: NettyApplicationCall,
        response: NettyApplicationResponse,
        bodySize: Int,
        requestMessageFuture: ChannelFuture
    ) {
        try {
            when (bodySize) {
                0 -> processEmpty(call, requestMessageFuture)
                in 1..65536 -> processSmallContent(call, response, bodySize)
                -1 -> processBodyFlusher(call, response, requestMessageFuture)
                else -> processBodyGeneral(call, response, requestMessageFuture)
            }
        } catch (actualException: Throwable) {
            processCallFailed(call, actualException)
        }
    }

    private fun processEmpty(call: NettyApplicationCall, lastFuture: ChannelFuture) {
        return finishCall(call, call.endOfStream(false), lastFuture)
    }

    private suspend fun processSmallContent(call: NettyApplicationCall, response: NettyApplicationResponse, size: Int) {
        val buffer = context.alloc().buffer(size)
        val channel = response.responseChannel
        val start = buffer.writerIndex()

        channel.readFully(buffer.nioBuffer(start, buffer.writableBytes()))
        buffer.writerIndex(start + size)

        val future = context.write(call.transform(buffer, true))
        val lastMessage = response.trailerMessage() ?: call.endOfStream(true)

        finishCall(call, lastMessage, future)
    }

    private suspend fun processBodyGeneral(
        call: NettyApplicationCall,
        response: NettyApplicationResponse,
        requestMessageFuture: ChannelFuture
    ) {
        val channel = response.responseChannel

        var unflushedBytes = 0
        var lastFuture: ChannelFuture = requestMessageFuture

        @Suppress("DEPRECATION")
        channel.lookAheadSuspend {
            while (true) {
                val buffer = request(0, 1)
                if (buffer == null) {
                    if (!awaitAtLeast(1)) break
                    continue
                }

                val rc = buffer.remaining()
                val buf = context.alloc().buffer(rc)
                val idx = buf.writerIndex()
                buf.setBytes(idx, buffer)
                buf.writerIndex(idx + rc)

                consumed(rc)
                unflushedBytes += rc

                val message = call.transform(buf, false)

                if (unflushedBytes >= UNFLUSHED_LIMIT) {
                    context.read()
                    val future = context.writeAndFlush(message)
                    lastFuture = future
                    future.suspendAwait()
                    unflushedBytes = 0
                } else {
                    lastFuture = context.write(message)
                }
            }
        }

        val lastMessage = response.trailerMessage() ?: call.endOfStream(false)
        finishCall(call, lastMessage, lastFuture)
    }

    private suspend fun processBodyFlusher(
        call: NettyApplicationCall,
        response: NettyApplicationResponse,
        requestMessageFuture: ChannelFuture
    ) {
        val channel = response.responseChannel

        var unflushedBytes = 0
        var lastFuture: ChannelFuture = requestMessageFuture

        @Suppress("DEPRECATION")
        channel.lookAheadSuspend {
            while (true) {
                val buffer = request(0, 1)
                if (buffer == null) {
                    // stops here! Fix this
                    if (!awaitAtLeast(1)) break
                    continue
                }

                val rc = buffer.remaining()
                val buf = context.alloc().buffer(rc)
                val idx = buf.writerIndex()
                buf.setBytes(idx, buffer)
                buf.writerIndex(idx + rc)

                consumed(rc)
                unflushedBytes += rc

                val message = call.transform(buf, false)

                if (unflushedBytes >= UNFLUSHED_LIMIT || channel.availableForRead == 0) {
                    context.read()
                    val future = context.writeAndFlush(message)
                    lastFuture = future
                    future.suspendAwait()
                    unflushedBytes = 0
                } else {
                    lastFuture = context.write(message)
                }
            }
        }

        val lastMessage = response.trailerMessage() ?: call.endOfStream(false)
        finishCall(call, lastMessage, lastFuture)
    }
}

@OptIn(InternalAPI::class)
private fun NettyApplicationResponse.isUpgradeResponse() =
    status()?.value == HttpStatusCode.SwitchingProtocols.value
