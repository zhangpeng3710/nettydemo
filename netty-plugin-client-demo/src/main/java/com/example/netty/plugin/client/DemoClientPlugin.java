package com.example.netty.plugin.client;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoClientPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(DemoClientPlugin.class);

    @Override
    public String getId() {
        return "demo-spi-client-plugin";
    }

    @Override
    public void onLoad() {
        log.info("[Client SPI Scaffold] DemoClientPlugin loaded!");
    }

    @Override
    public void onUnload() {
        log.info("[Client SPI Scaffold] DemoClientPlugin unloaded.");
    }

    @Override
    public boolean supports(MessagePacket packet) {
        // Intercept PONG messages for secondary processing (e.g. latency metrics)
        return packet.getType() == MessageType.PONG;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        log.info("[Client SPI Scaffold][{}][PONG-Interception] Captured Heartbeat Pong in SPI Plugin! Timestamp={}",
                connectionType, packet.getPong().getTimestamp());
        // Return false to let the main client heartbeat handler continue processing it
        return false;
    }
}
