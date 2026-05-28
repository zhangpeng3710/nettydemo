package com.example.netty.server.handler;

import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Ping;
import com.example.netty.common.proto.Pong;
import com.example.netty.common.proto.Request;
import com.example.netty.common.proto.Response;
import io.netty.channel.ChannelFutureListener;
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

    // Thread-safe channel group to track all active client channels
    public static final ChannelGroup activeChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public ServerHandler(String connectionType) {
        this.connectionType = connectionType;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessagePacket packet) throws Exception {
        log.info("[Server][{}] Received message: type={}, seq={}", connectionType, packet.getType(), packet.getSequence());

        switch (packet.getType()) {
            case PING:
                handlePing(ctx, packet);
                break;
            case REQUEST:
                handleRequest(ctx, packet);
                break;
            default:
                log.warn("[Server][{}] Unhandled message type: {}", connectionType, packet.getType());
                break;
        }
    }

    private void handlePing(ChannelHandlerContext ctx, MessagePacket packet) {
        Ping ping = packet.getPing();
        log.info("[Server][{}] Received Ping timestamp: {}", connectionType, ping.getTimestamp());

        MessagePacket responsePacket = MessagePacket.newBuilder()
                .setType(MessageType.PONG)
                .setSequence(packet.getSequence())
                .setPong(Pong.newBuilder().setTimestamp(System.currentTimeMillis()).build())
                .build();

        ctx.writeAndFlush(responsePacket);
    }

    private void handleRequest(ChannelHandlerContext ctx, MessagePacket packet) {
        Request req = packet.getRequest();
        log.info("[Server][{}] Received Request ID: {}, Command: {}, Data: {}", 
                connectionType, req.getReqId(), req.getCommand(), req.getData());

        MessagePacket responsePacket = MessagePacket.newBuilder()
                .setType(MessageType.RESPONSE)
                .setSequence(packet.getSequence())
                .setResponse(Response.newBuilder()
                        .setReqId(req.getReqId())
                        .setCode(200)
                        .setMessage("Success")
                        .setData("Server processed command: " + req.getCommand() + " via " + connectionType)
                        .build())
                .build();

        if ("SHORT".equalsIgnoreCase(connectionType)) {
            // For Short Connection, close the connection immediately after the response is written
            ctx.writeAndFlush(responsePacket).addListener(ChannelFutureListener.CLOSE);
            log.info("[Server][SHORT] Wrote response. Closing connection as requested by SHORT connection protocol.");
        } else {
            // Keep connection alive
            ctx.writeAndFlush(responsePacket);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Server][{}] Channel active: {}", connectionType, ctx.channel().remoteAddress());
        // Track the connection in the activeChannels group
        activeChannels.add(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Server][{}] Channel inactive: {}", connectionType, ctx.channel().remoteAddress());
        // ChannelGroup automatically removes inactive channels
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[Server][{}] Exception caught: {}", connectionType, cause.getMessage(), cause);
        ctx.close();
    }
}
