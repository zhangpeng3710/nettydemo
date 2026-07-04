package com.example.netty.server.controller;

import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import com.example.netty.common.proto.UpgradeCommand;
import com.example.netty.server.handler.ServerHandler;
import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.net.URL;
import java.util.Date;

@RestController
public class UpgradeController {

    private static final Logger log = LoggerFactory.getLogger(UpgradeController.class);

    @Value("${oss.bucket-name}")
    private String bucketName;

    @Autowired
    private OSS ossClient;

    @Value("${upgrade.latest-version:1.0.1}")
    private String latestVersion;

    @Value("${upgrade.strategy:AUTO_DOWNLOAD_MANUAL_UPDATE}")
    private String upgradeStrategy;

    @Value("${upgrade.download-url:https://tomin.oss-cn-beijing.aliyuncs.com/upgrade-dir/digest.txt}")
    private String downloadUrl;

    @GetMapping("/upgrade/sign")
    public String getPresignedUrl(@RequestParam String key) {
        log.info("REST: Requesting presigned URL for key: {}", key);
        if (key == null || !key.startsWith("upgrade-dir/")) {
            log.error("REST: Invalid key parameter: {}", key);
            throw new IllegalArgumentException("Invalid key path. Must start with 'upgrade-dir/'");
        }
        Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000);
        URL signedUrl = ossClient.generatePresignedUrl(bucketName, key, expiration, HttpMethod.GET);
        return signedUrl.toString();
    }

    @GetMapping("/upgrade/check")
    public UpgradeCheckResponse checkUpgrade(
            @RequestParam String version,
            @RequestParam String platform,
            @RequestParam String channel,
            @RequestParam(required = false) String clientId) {
        log.info("[Server][Upgrade] Received check request: version={}, platform={}, channel={}, clientId={}",
                version, platform, channel, clientId);

        boolean updateAvailable = isNewerVersion(version, latestVersion);

        UpgradeCheckResponse response = new UpgradeCheckResponse();
        response.setUpdateAvailable(updateAvailable);
        response.setVersion(latestVersion);
        response.setDownloadUrl(downloadUrl);
        response.setStrategy(upgradeStrategy);
        response.setForce(false);

        log.info("[Server][Upgrade] Checked version result: updateAvailable={}, latest={}, strategy={}",
                updateAvailable, latestVersion, upgradeStrategy);
        return response;
    }

    private boolean isNewerVersion(String current, String latest) {
        if (current == null || latest == null) return false;
        try {
            // Clean up any non-numeric suffixes (e.g. -SNAPSHOT) for comparison
            String cleanCurr = current.split("-")[0];
            String cleanLate = latest.split("-")[0];
            String[] currParts = cleanCurr.split("\\.");
            String[] lateParts = cleanLate.split("\\.");
            int length = Math.max(currParts.length, lateParts.length);
            for (int i = 0; i < length; i++) {
                int currVer = i < currParts.length ? Integer.parseInt(currParts[i].replaceAll("[^0-9]", "")) : 0;
                int lateVer = i < lateParts.length ? Integer.parseInt(lateParts[i].replaceAll("[^0-9]", "")) : 0;
                if (currVer < lateVer) return true;
                if (currVer > lateVer) return false;
            }
        } catch (Exception e) {
            log.warn("[Server][Upgrade] Failed to parse version strings, fallback to string comparison: current={}, latest={}", current, latest);
            return !current.equalsIgnoreCase(latest);
        }
        return false;
    }

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

    public static class UpgradeCheckResponse {
        private boolean updateAvailable;
        private String version;
        private String downloadUrl;
        private String strategy;
        private boolean force;

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        public void setUpdateAvailable(boolean updateAvailable) {
            this.updateAvailable = updateAvailable;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }
    }
}

