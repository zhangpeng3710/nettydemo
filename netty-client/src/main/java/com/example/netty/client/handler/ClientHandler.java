package com.example.netty.client.handler;

import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Ping;
import com.example.netty.common.proto.Pong;
import com.example.netty.common.proto.Response;
import com.example.netty.common.proto.UpgradeCommand;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

public class ClientHandler extends SimpleChannelInboundHandler<MessagePacket> {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final String connectionType;
    private final AtomicLong seqGenerator = new AtomicLong(1);

    public ClientHandler(String connectionType) {
        this.connectionType = connectionType;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessagePacket packet) throws Exception {
        log.info("[Client][{}] Received response packet: type={}, seq={}", connectionType, packet.getType(), packet.getSequence());

        switch (packet.getType()) {
            case PONG:
                Pong pong = packet.getPong();
                log.info("[Client][{}] Heartbeat Pong received! Timestamp: {}", connectionType, pong.getTimestamp());
                break;
            case RESPONSE:
                Response resp = packet.getResponse();
                log.info("[Client][{}] Business Response: reqId={}, code={}, message='{}', data='{}'",
                        connectionType, resp.getReqId(), resp.getCode(), resp.getMessage(), resp.getData());
                break;
            case UPGRADE:
                handleUpgrade(ctx, packet);
                break;
            default:
                log.warn("[Client][{}] Received unknown packet type: {}", connectionType, packet.getType());
                break;
        }
    }

    private void handleUpgrade(ChannelHandlerContext ctx, MessagePacket packet) {
        UpgradeCommand upgradeCmd = packet.getUpgradeCommand();
        log.info("[Client][{}] =========================================", connectionType);
        log.info("[Client][{}] RECEIVED UPGRADE COMMAND FROM SERVER!", connectionType);
        log.info("[Client][{}] Target Version: {}", connectionType, upgradeCmd.getVersion());
        log.info("[Client][{}] Download URL: {}", connectionType, upgradeCmd.getDownloadUrl());
        log.info("[Client][{}] Force Upgrade: {}", connectionType, upgradeCmd.getForce());
        log.info("[Client][{}] =========================================", connectionType);

        try {
            log.info("[Client][{}] Spawning upgrade launcher process...", connectionType);

            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            
            // Paths targeting the gradle output JAR. In a distribution, these will be in the same folder.
            String launcherJar = "d:/Projects/code/nettydemo/netty-client-upgrade/build/libs/netty-client-upgrade-1.0.0.jar";
            File jarFile = new File(launcherJar);
            
            String classpath = jarFile.exists() ? launcherJar : 
                "d:/Projects/code/nettydemo/netty-client-upgrade/build/classes/java/main;" +
                "C:/Users/zhang/.gradle/caches/modules-2/files-2.1/com.threerings/getdown-core/1.8.6/d4ba389f41753995ad40ec2c2d43ee9e97ee9cf2/getdown-core-1.8.6.jar";

            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "com.example.netty.upgrade.CustomLauncher"
            );
            
            pb.directory(new File("d:/Projects/code/nettydemo"));
            pb.inheritIO();
            pb.start();
            
            log.info("[Client][{}] Upgrade process spawned. Exiting client process to allow jar swap...", connectionType);
            ctx.close().addListener(f -> {
                System.exit(0);
            });
            
        } catch (Exception e) {
            log.error("[Client][{}] Failed to launch upgrade process", connectionType, e);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                log.info("[Client][{}] Connection idle (no write). Sending Heartbeat Ping...", connectionType);
                MessagePacket pingPacket = MessagePacket.newBuilder()
                        .setType(MessageType.PING)
                        .setSequence(seqGenerator.incrementAndGet())
                        .setPing(Ping.newBuilder().setTimestamp(System.currentTimeMillis()).build())
                        .build();
                ctx.writeAndFlush(pingPacket);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Client][{}] Channel active: connected to server {}", connectionType, ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[Client][{}] Channel inactive: connection closed.", connectionType);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[Client][{}] Exception caught: {}", connectionType, cause.getMessage(), cause);
        ctx.close();
    }
}
