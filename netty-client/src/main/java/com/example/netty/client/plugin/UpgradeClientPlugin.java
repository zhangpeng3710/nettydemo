package com.example.netty.client.plugin;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.UpgradeCommand;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class UpgradeClientPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(UpgradeClientPlugin.class);

    @Override
    public String getId() {
        return "upgrade-client";
    }

    @Override
    public boolean supports(MessagePacket packet) {
        return packet.getType() == MessageType.UPGRADE;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        UpgradeCommand upgradeCmd = packet.getUpgradeCommand();
        log.info("[Client][{}][Plugin] =========================================", connectionType);
        log.info("[Client][{}][Plugin] RECEIVED UPGRADE COMMAND FROM SERVER!", connectionType);
        log.info("[Client][{}][Plugin] Target Version: {}", connectionType, upgradeCmd.getVersion());
        log.info("[Client][{}][Plugin] Download URL: {}", connectionType, upgradeCmd.getDownloadUrl());
        log.info("[Client][{}][Plugin] Force Upgrade: {}", connectionType, upgradeCmd.getForce());
        log.info("[Client][{}][Plugin] =========================================", connectionType);

        try {
            log.info("[Client][{}][Plugin] Spawning upgrade launcher process...", connectionType);

            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            
            File workspaceRoot = findWorkspaceRoot();
            String classpath;
            File workingDir;

            if (workspaceRoot != null) {
                // Development mode
                workingDir = workspaceRoot;
                log.info("[Client][{}][Plugin] Detected workspace root at {}", connectionType, workspaceRoot.getAbsolutePath());
                
                String pathSeparator = File.pathSeparator;
                File launcherJar = new File(workspaceRoot, "netty-client-upgrade/build/libs/netty-client-upgrade-1.0.0.jar");
                
                if (launcherJar.exists()) {
                    classpath = launcherJar.getAbsolutePath() + pathSeparator +
                                new File(workspaceRoot, "netty-client-upgrade/build/libs/*").getAbsolutePath();
                } else {
                    // Fallback if the jar is not yet built, but class files exist
                    classpath = new File(workspaceRoot, "netty-client-upgrade/build/classes/java/main").getAbsolutePath() + pathSeparator +
                                new File(workspaceRoot, "netty-client-upgrade/build/libs/*").getAbsolutePath();
                }
            } else {
                // Production / Distribution mode
                workingDir = new File(System.getProperty("user.dir"));
                log.info("[Client][{}][Plugin] Running in distribution mode. Working dir: {}", connectionType, workingDir.getAbsolutePath());
                
                String pathSeparator = File.pathSeparator;
                // Look for netty-client-upgrade jar in current directory or libs folder
                File localJar = new File("netty-client-upgrade-1.0.0.jar");
                File libsJar = new File("libs/netty-client-upgrade-1.0.0.jar");
                
                if (libsJar.exists()) {
                    classpath = "libs/*";
                } else if (localJar.exists()) {
                    classpath = "*";
                } else {
                    // Standard fallback: assume files are in current dir or libs
                    classpath = "netty-client-upgrade-1.0.0.jar" + pathSeparator + "libs/*" + pathSeparator + "*";
                }
            }

            log.info("[Client][{}][Plugin] Constructed classpath: {}", connectionType, classpath);

            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "com.example.netty.upgrade.CustomLauncher"
            );
            
            pb.directory(workingDir);
            pb.inheritIO();
            pb.start();
            
            log.info("[Client][{}][Plugin] Upgrade process spawned. Exiting client process to allow jar swap...", connectionType);
            ctx.close().addListener(f -> {
                System.exit(0);
            });
            
        } catch (Exception e) {
            log.error("[Client][{}][Plugin] Failed to launch upgrade process", connectionType, e);
        }
        return true;
    }

    File findWorkspaceRoot() {
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            if (new File(dir, "settings.gradle").exists()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
