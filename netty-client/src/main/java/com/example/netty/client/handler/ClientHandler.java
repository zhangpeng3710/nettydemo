package com.example.netty.client.handler;

import com.example.netty.common.plugin.PluginRegistry;
import com.example.netty.common.proto.MessagePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientHandler extends SimpleChannelInboundHandler<MessagePacket> {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final String connectionType;
    private final PluginRegistry pluginRegistry;

    public ClientHandler(String connectionType, PluginRegistry pluginRegistry) {
        this.connectionType = connectionType;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessagePacket packet) throws Exception {
        log.info("[Client][{}] Received response packet: type={}, seq={}", connectionType, packet.getType(), packet.getSequence());
        pluginRegistry.dispatchMessage(ctx, packet, connectionType);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        pluginRegistry.dispatchUserEvent(ctx, evt, connectionType);
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Client][{}] Channel active: connected to server {}", connectionType, ctx.channel().remoteAddress());
        pluginRegistry.dispatchActive(ctx, connectionType);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Client][{}] Channel inactive: connection closed.", connectionType);
        pluginRegistry.dispatchInactive(ctx, connectionType);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[Client][{}] Exception caught: {}", connectionType, cause.getMessage(), cause);
        pluginRegistry.dispatchException(ctx, cause, connectionType);
        ctx.close();
    }
}

