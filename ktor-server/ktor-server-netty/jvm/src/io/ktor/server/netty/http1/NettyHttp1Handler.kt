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
import io.ktor.utils.io.core.internal.*
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.*
import kotlinx.coroutines.*
import java.io.*
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

    private var configured = false
    private var skipEmpty = false

    lateinit var responseWriter: NettyResponsePipeline
    private var currentRequest: ByteChannel? = null

    @OptIn(InternalAPI::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        if (!configured) {
            configured = true
            responseWriter = NettyResponsePipeline(ctx, WriterEncapsulation.Http1, coroutineContext)

            ctx.pipeline().apply {
                addLast(callEventGroup, NettyApplicationCallHandler(userContext, enginePipeline, environment.log))
            }
        }
        super.channelActive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            handleRequest(ctx, msg)
        } else if (msg is LastHttpContent && !msg.content().isReadable && skipEmpty) {
            skipEmpty = false
            msg.release()
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (configured) {
            configured = false
            ctx.pipeline().apply {
                remove(NettyApplicationCallHandler::class.java)
            }
        }
        super.channelInactive(ctx)
    }

    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is IOException || cause is ChannelIOException) {
            environment.application.log.debug("I/O operation failed", cause)
            handlerJob.cancel()
        } else {
            handlerJob.completeExceptionally(cause)
        }
        ctx.close()
    }

    private fun handleRequest(context: ChannelHandlerContext, message: HttpRequest) {
//        context.channel().config().isAutoRead = false

        val requestBodyChannel = when {
            message is LastHttpContent && !message.content().isReadable -> null
            message.method() === HttpMethod.GET &&
                !HttpUtil.isContentLengthSet(message) && !HttpUtil.isTransferEncodingChunked(message) -> {
                skipEmpty = true
                null
            }
            else -> {
                val channel = ByteChannel()
                currentRequest = channel
                // refactor this
                channel
            }
        }

        val call = NettyHttp1ApplicationCall(
            environment.application,
            context,
            message,
            requestBodyChannel,
            engineContext,
            userContext
        )

        if (message is HttpContent) {
            content(context, message)
        }
        responseWriter.processResponse(call)
    }

    private fun content(context: ChannelHandlerContext, message: HttpContent) {
        try {
            val contentBuffer = message.content()
            pipeBuffer(context, contentBuffer)

            if (message is LastHttpContent) {
                currentRequest?.close()
            }
        } finally {
            message.release()
        }
    }


    private fun pipeBuffer(context: ChannelHandlerContext, message: ByteBuf) {
        if (message.readableBytes() == 0) return

        currentRequest!!.writeByteBuf(context, message)

        context.channel().config().isAutoRead = currentRequest!!.availableForWrite != 0
    }
}

internal fun ByteWriteChannel.writeByteBuf(context: ChannelHandlerContext, buffer: ByteBuf) {
    val length = buffer.readableBytes()
    if (length == 0) return

    val bytes = buffer.internalNioBuffer(buffer.readerIndex(), length)

    // what to do?
    runBlocking {
        launch(NettyDispatcher.CurrentContext(context)) {
            writeFully(bytes)
        }
    }
}
