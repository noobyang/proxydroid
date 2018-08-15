package me.bwelco.proxy.proxy

import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.Promise
import me.bwelco.proxy.CustomNioSocketChannel
import me.bwelco.proxy.http.HttpInterceptor
import me.bwelco.proxy.http.HttpInterceptorMatcher
import me.bwelco.proxy.tls.SSLFactory
import me.bwelco.proxy.upstream.RelayHandler
import me.bwelco.proxy.util.addFutureListener

class HttpsUpstreamProxy(val request: Socks5CommandRequest,
                         val promise: Promise<Channel>,
                         val interceptorMatcher: HttpInterceptorMatcher) : ChannelInboundHandlerAdapter() {

    private val bootstrap: Bootstrap by lazy { Bootstrap() }
    private lateinit var thisClientHandlerCtx: ChannelHandlerContext

    override fun channelActive(ctx: ChannelHandlerContext) {
        val clientChannel = ctx.channel()
        thisClientHandlerCtx = ctx

        bootstrap.group(clientChannel.eventLoop())
                .channel(CustomNioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(ConnectHandler())

        bootstrap.connect(request.dstAddr(), request.dstPort()).addFutureListener {
            if (it.isSuccess) thisClientHandlerCtx.channel().pipeline().remove(this)
        }
    }

    inner class ConnectHandler : ChannelInboundHandlerAdapter() {
        override fun channelActive(ctx: ChannelHandlerContext) {
            val outboundChannel = ctx.channel()
            promise.setSuccess(ctx.channel())

            outboundChannel.pipeline().remove(this)
            // start to relay data transparently

            val downStreamSSLContext = SslContextBuilder
                    .forServer(SSLFactory.certConfig.serverPrivateKey,
                            SSLFactory.newCert(request.dstAddr()))
                    .build()

            val upstreamSSLContext = SslContextBuilder.forClient().build()
            val outerPipeline = outboundChannel.pipeline()

            // upstream ssl
            outerPipeline.addFirst(upstreamSSLContext.newHandler(outboundChannel.alloc()))

            // upstream in
            outerPipeline.addLast(LoggingHandler())
            outerPipeline.addLast(HttpResponseDecoder())
            outerPipeline.addLast(HttpObjectAggregator(1024 * 1024 * 64))
            outerPipeline.addLast(RelayHandler(thisClientHandlerCtx.channel()))

            // upstream out
            outerPipeline.addLast(HttpRequestEncoder())

            // downstream ssl
            thisClientHandlerCtx.pipeline().addFirst(downStreamSSLContext.newHandler(ctx.alloc()))

            // downstream out
            thisClientHandlerCtx.pipeline().addLast(HttpResponseEncoder())

            // downstream in
            thisClientHandlerCtx.pipeline().addLast(HttpRequestDecoder())
            thisClientHandlerCtx.pipeline().addLast(HttpObjectAggregator(1024 * 1024 * 64))
            thisClientHandlerCtx.pipeline().addLast("HostSelector", HostSelector(interceptorMatcher, request.dstAddr()))
            thisClientHandlerCtx.pipeline().addLast(RelayHandler(outboundChannel))


            outboundChannel.pipeline().fireChannelActive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            promise.setFailure(cause)
        }
    }

    class HttpInterceptorHandler(val httpInterceptor: HttpInterceptor) : ChannelDuplexHandler() {

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is FullHttpRequest) {
                httpInterceptor.onRequest(msg)
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is FullHttpResponse) {
                httpInterceptor.onResponse(msg)
            }
            ctx.write(msg, promise)
        }

    }

    class HostSelector(val interceptorMatcher: HttpInterceptorMatcher, val remoteHost: String) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is FullHttpRequest) {
                interceptorMatcher.match(remoteHost)?.apply {
                    ctx.pipeline().addAfter(
                            "HostSelector",
                            "HttpInterceptorHandler",
                            HttpInterceptorHandler(this))
                }
            }

            ctx.fireChannelRead(msg)
        }
    }
}