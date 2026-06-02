package com.example.netty.server.handler;

import com.example.netty.common.plugin.PluginRegistry;
import com.example.netty.common.proto.MessagePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerHandler extends SimpleChannelInboundHandler<MessagePacket> {

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);
    private final String connectionType; // "TCP", "TLS", or "SHORT"
    private final PluginRegistry pluginRegistry;

    // Thread-safe channel group to track all active client channels
    public static final ChannelGroup activeChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public ServerHandler(String connectionType, PluginRegistry pluginRegistry) {
        this.connectionType = connectionType;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessagePacket packet) throws Exception {
        log.info("[Server][{}] Received message: type={}, seq={}", connectionType, packet.getType(), packet.getSequence());
        pluginRegistry.dispatchMessage(ctx, packet, connectionType);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Server][{}] Channel active: {}", connectionType, ctx.channel().remoteAddress());
        // Track the connection in the activeChannels group
        activeChannels.add(ctx.channel());
        pluginRegistry.dispatchActive(ctx, connectionType);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Server][{}] Channel inactive: {}", connectionType, ctx.channel().remoteAddress());
        pluginRegistry.dispatchInactive(ctx, connectionType);
        // ChannelGroup automatically removes inactive channels
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[Server][{}] Exception caught: {}", connectionType, cause.getMessage(), cause);
        pluginRegistry.dispatchException(ctx, cause, connectionType);
        ctx.close();
    }
}

