package com.example.netty.server.plugin;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Ping;
import com.example.netty.common.proto.Pong;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PingPongServerPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(PingPongServerPlugin.class);

    @Override
    public String getId() {
        return "ping-pong-server";
    }

    @Override
    public boolean supports(MessagePacket packet) {
        return packet.getType() == MessageType.PING;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        Ping ping = packet.getPing();
        log.info("[Server][{}][Plugin] Received Ping timestamp: {}", connectionType, ping.getTimestamp());

        MessagePacket responsePacket = MessagePacket.newBuilder()
                .setType(MessageType.PONG)
                .setSequence(packet.getSequence())
                .setPong(Pong.newBuilder().setTimestamp(System.currentTimeMillis()).build())
                .build();

        ctx.writeAndFlush(responsePacket);
        return true;
    }
}
