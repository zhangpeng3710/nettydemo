package com.example.netty.client.plugin;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Ping;
import com.example.netty.common.proto.Pong;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class HeartbeatClientPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatClientPlugin.class);
    private final AtomicLong seqGenerator = new AtomicLong(1);

    @Override
    public String getId() {
        return "heartbeat-client";
    }

    @Override
    public boolean supports(MessagePacket packet) {
        return packet.getType() == MessageType.PONG;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        Pong pong = packet.getPong();
        log.info("[Client][{}][Plugin] Heartbeat Pong received! Timestamp: {}", connectionType, pong.getTimestamp());
        return true;
    }

    @Override
    public void onUserEventTriggered(ChannelHandlerContext ctx, Object evt, String connectionType) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                log.info("[Client][{}][Plugin] Connection idle (no write). Sending Heartbeat Ping...", connectionType);
                MessagePacket pingPacket = MessagePacket.newBuilder()
                        .setType(MessageType.PING)
                        .setSequence(seqGenerator.incrementAndGet())
                        .setPing(Ping.newBuilder().setTimestamp(System.currentTimeMillis()).build())
                        .build();
                ctx.writeAndFlush(pingPacket);
            }
        }
    }
}
