package com.example.netty.server.controller;

import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.UpgradeCommand;
import com.example.netty.server.handler.ServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UpgradeController {

    private static final Logger log = LoggerFactory.getLogger(UpgradeController.class);

    @GetMapping("/upgrade-client")
    public String triggerUpgrade(@RequestParam(defaultValue = "1.0.1") String version) {
        log.info("REST: Triggering client upgrade command for version: {}", version);

        MessagePacket upgradePacket = MessagePacket.newBuilder()
                .setType(MessageType.UPGRADE)
                .setSequence(System.currentTimeMillis())
                .setUpgradeCommand(UpgradeCommand.newBuilder()
                        .setVersion(version)
                        .setDownloadUrl("http://localhost:8080/upgrade/netty-client.jar")
                        .setForce(true)
                        .build())
                .build();

        // Broadcast to all active channels
        int count = ServerHandler.activeChannels.size();
        ServerHandler.activeChannels.writeAndFlush(upgradePacket);

        log.info("REST: Upgrade command broadcasted to {} active channels.", count);
        return "Upgrade command broadcasted to " + count + " client(s) for version " + version + ".\n";
    }
}
