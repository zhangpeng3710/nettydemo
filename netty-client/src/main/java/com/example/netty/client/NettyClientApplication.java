package com.example.netty.client;

import com.example.netty.client.core.NettyClient;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.Request;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;

@SpringBootApplication
@EnableScheduling
public class NettyClientApplication implements CommandLineRunner {


    private static final Logger log = LoggerFactory.getLogger(NettyClientApplication.class);

    private static FileLock instanceLock;
    private static FileChannel lockChannel;
    private static RandomAccessFile lockFileRaf;

    @Autowired
    private NettyClient client;

    public static void main(String[] args) {
        if (!acquireSingleInstanceLock()) {
            System.err.println("[Client] Another client instance is already running! Exiting...");
            System.exit(0);
        }
        SpringApplication.run(NettyClientApplication.class, args);
    }

    private static boolean acquireSingleInstanceLock() {
        try {
            File lockFile = new File("netty-client.lock");
            lockFileRaf = new RandomAccessFile(lockFile, "rw");
            lockChannel = lockFileRaf.getChannel();
            // tryLock is non-blocking, returns null if already locked
            instanceLock = lockChannel.tryLock();
            if (instanceLock != null) {
                log.info("[Client] Acquired single instance lock successfully on netty-client.lock");
                // Register a shutdown hook to release the lock on JVM exit
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    releaseSingleInstanceLock();
                }));
                return true;
            }
        } catch (Exception e) {
            System.err.println("[Client] Failed to acquire single instance lock: " + e.getMessage());
        }
        return false;
    }

    private static void releaseSingleInstanceLock() {
        try {
            log.info("[Client] Releasing single instance lock on netty-client.lock...");
            if (instanceLock != null && instanceLock.isValid()) {
                instanceLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
            if (lockFileRaf != null) {
                lockFileRaf.close();
            }
            File lockFile = new File("netty-client.lock");
            if (lockFile.exists()) {
                lockFile.delete();
            }
        } catch (Exception e) {
            // Ignore exceptions during shutdown hook
        }
    }


    @Override
    public void run(String... args) throws Exception {
        try {
            client.start();
            Channel channel = client.getChannel();

            // Send a test business request
            log.info("Sending test request to server...");
            MessagePacket testRequest = MessagePacket.newBuilder()
                    .setType(MessageType.REQUEST)
                    .setSequence(1001L)
                    .setRequest(Request.newBuilder()
                            .setReqId(UUID.randomUUID().toString())
                            .setCommand("HelloServer")
                            .setData("Hello from Netty Client using " + client.getMode())
                            .build())
                    .build();

            channel.writeAndFlush(testRequest);

            // Send custom SPI Demo request to test DemoServerPlugin
            Thread.sleep(1000);
            log.info("Sending custom SPIDemoCommand request to server...");
            MessagePacket spiDemoRequest = MessagePacket.newBuilder()
                    .setType(MessageType.REQUEST)
                    .setSequence(1002L)
                    .setRequest(Request.newBuilder()
                            .setReqId(UUID.randomUUID().toString())
                            .setCommand("SPIDemoCommand")
                            .setData("Hello SPI Server Plugin!")
                            .build())
                    .build();
            channel.writeAndFlush(spiDemoRequest);

            if ("SHORT".equalsIgnoreCase(client.getMode())) {
                log.info("SHORT mode: Waiting for server response and connection close...");
                // Keep main thread alive for a few seconds to print logs
                Thread.sleep(3000);
                client.stop();
            } else {
                log.info("Long Connection (TCP/TLS) mode: Connection active, keeping client alive for testing heartbeats...");
                // Keep the client running for testing heartbeats
                // Wait for 120 seconds so we can see multiple heartbeats and trigger upgrade, then stop the client.
                Thread.sleep(120000);
                log.info("Demo interval completed. Stopping client.");
                client.stop();
            }
        } catch (Exception e) {
            log.error("Client error", e);
        }
    }
}
