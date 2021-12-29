/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.netty.http1

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.cio.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.internal.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.channels.*
import kotlin.coroutines.*

internal class NettyHttp1Handler(
    private val enginePipeline: EnginePipeline,
    private val environment: ApplicationEngineEnvironment,
    private val callEventGroup: EventExecutorGroup,
    private val engineContext: CoroutineContext,
    private val userContext: CoroutineContext
) : ChannelInboundHandlerAdapter(), CoroutineScope {
    private val handlerJob = CompletableDeferred<Nothing>()

    override val coroutineContext: CoroutineContext get() = handlerJob

    private var skipEmpty = false

    lateinit var responseWriter: NettyResponsePipeline

    private var currentRequest: ByteChannel? = null

    @OptIn(InternalAPI::class)
    override fun channelActive(context: ChannelHandlerContext) {
        responseWriter = NettyResponsePipeline(context, WriterEncapsulation.Http1, coroutineContext)

        context.pipeline().apply {
            addLast(callEventGroup, NettyApplicationCallHandler(userContext, enginePipeline, environment.log))
        }
        //what is the diff super. and ctx.fire...
        context.fireChannelActive()
    }

    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        responseWriter.markReadingStarted()

        when (message) {
            is HttpRequest -> handleRequest(context, message)
            is HttpContent -> handleContent(context, message)
            is ByteBuf -> pipeBuffer(context, message)
            else -> {
                context.fireChannelRead(message)
            }
        }
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        context.pipeline().remove(NettyApplicationCallHandler::class.java)
        context.fireChannelInactive()
    }

    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        if (cause is IOException || cause is ChannelIOException) {
            environment.application.log.debug("I/O operation failed", cause)
            handlerJob.cancel()
        } else {
            handlerJob.completeExceptionally(cause)
        }
        context.close()
    }

    override fun channelReadComplete(context: ChannelHandlerContext?) {
        responseWriter.markReadingStopped()
        super.channelReadComplete(context)
    }

    private fun handleRequest(context: ChannelHandlerContext, message: HttpRequest) {
        val call = getNettyApplicationCall(context, message)

        if (message is HttpContent) {
            handleContent(context, message)
        }

        context.fireChannelRead(call)
        responseWriter.processResponse(call)
    }

    private fun getNettyApplicationCall(
        context: ChannelHandlerContext,
        message: HttpRequest
    ): NettyHttp1ApplicationCall {
        val requestBodyChannel = when {
            message is LastHttpContent && !message.content().isReadable -> null
            message.method() === HttpMethod.GET &&
                !HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) -> {
                skipEmpty = true
                null
            }
            else -> ByteChannel()
        }?.also {
            currentRequest = it
        }

        return NettyHttp1ApplicationCall(
            environment.application,
            context,
            message,
            requestBodyChannel,
            engineContext,
            userContext
        )
    }

    private fun handleContent(context: ChannelHandlerContext, message: HttpContent) {
        try {
            val contentBuffer = message.content()
            pipeBuffer(context, contentBuffer)

            // upgraded??
            if (message is LastHttpContent) {
                currentRequest?.close()
                currentRequest = null
            }

            context.fireChannelRead(message)
        } finally {
            message.release()
        }
    }

    private fun pipeBuffer(context: ChannelHandlerContext, message: ByteBuf) {
        val length = message.readableBytes()
        if (length == 0) return

        //what to do with launch?
        launch(context.executor().asCoroutineDispatcher()) {
            val buffer = message.internalNioBuffer(message.readerIndex(), length)
            currentRequest?.writeFully(buffer)

            context.channel().config().isAutoRead = currentRequest!!.availableForWrite != 0
        }
    }
}
