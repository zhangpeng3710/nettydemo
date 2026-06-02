package com.example.netty.server.plugin;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Request;
import com.example.netty.common.proto.Response;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BusinessRequestServerPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(BusinessRequestServerPlugin.class);

    @Override
    public String getId() {
        return "business-request-server";
    }

    @Override
    public boolean supports(MessagePacket packet) {
        return packet.getType() == MessageType.REQUEST;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        Request req = packet.getRequest();
        log.info("[Server][{}][Plugin] Received Request ID: {}, Command: {}, Data: {}", 
                connectionType, req.getReqId(), req.getCommand(), req.getData());

        MessagePacket responsePacket = MessagePacket.newBuilder()
                .setType(MessageType.RESPONSE)
                .setSequence(packet.getSequence())
                .setResponse(Response.newBuilder()
                        .setReqId(req.getReqId())
                        .setCode(200)
                        .setMessage("Success")
                        .setData("Server processed command: " + req.getCommand() + " via " + connectionType + " [Plugin]")
                        .build())
                .build();

        if ("SHORT".equalsIgnoreCase(connectionType)) {
            // For Short Connection, close the connection immediately after the response is written
            ctx.writeAndFlush(responsePacket).addListener(ChannelFutureListener.CLOSE);
            log.info("[Server][SHORT][Plugin] Wrote response. Closing connection as requested by SHORT connection protocol.");
        } else {
            // Keep connection alive
            ctx.writeAndFlush(responsePacket);
        }
        return true;
    }
}
