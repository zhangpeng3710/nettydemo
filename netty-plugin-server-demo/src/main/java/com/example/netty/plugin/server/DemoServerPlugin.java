package com.example.netty.plugin.server;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Request;
import com.example.netty.common.proto.Response;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoServerPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(DemoServerPlugin.class);

    @Override
    public String getId() {
        return "demo-spi-server-plugin";
    }

    @Override
    public void onLoad() {
        log.info("[Server SPI Scaffold] DemoServerPlugin loaded!");
    }

    @Override
    public void onUnload() {
        log.info("[Server SPI Scaffold] DemoServerPlugin unloaded.");
    }

    @Override
    public boolean supports(MessagePacket packet) {
        // Only handle REQUEST packets with command "SPIDemoCommand"
        if (packet.getType() == MessageType.REQUEST) {
            Request req = packet.getRequest();
            return "SPIDemoCommand".equalsIgnoreCase(req.getCommand());
        }
        return false;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        Request req = packet.getRequest();
        log.info("[Server SPI Scaffold][{}][SPIDemo] Handling custom command in SPI Plugin! Data={}", 
                connectionType, req.getData());

        MessagePacket responsePacket = MessagePacket.newBuilder()
                .setType(MessageType.RESPONSE)
                .setSequence(packet.getSequence())
                .setResponse(Response.newBuilder()
                        .setReqId(req.getReqId())
                        .setCode(200)
                        .setMessage("Success")
                        .setData("Response from DemoServerPlugin via " + connectionType)
                        .build())
                .build();

        ctx.writeAndFlush(responsePacket);
        
        // Return true to signal that this packet has been fully handled and should not propagate further
        return true;
    }

    @Override
    public int getOrder() {
        // Give it a higher priority so it runs before the default server handler plugin
        return -10;
    }
}
