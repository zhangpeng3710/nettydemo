package com.example.netty.client.plugin;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Response;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BusinessResponseClientPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(BusinessResponseClientPlugin.class);

    @Override
    public String getId() {
        return "business-response-client";
    }

    @Override
    public boolean supports(MessagePacket packet) {
        return packet.getType() == MessageType.RESPONSE;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        Response resp = packet.getResponse();
        log.info("[Client][{}][Plugin] Business Response: reqId={}, code={}, message='{}', data='{}'",
                connectionType, resp.getReqId(), resp.getCode(), resp.getMessage(), resp.getData());
        return true;
    }
}
