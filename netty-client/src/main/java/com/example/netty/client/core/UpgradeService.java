package com.example.netty.client.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.SM2;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 客户端核心基础服务：管理版本查询、静默下载、国密安全校验、预约升级及优雅停机。
 */
@Service
public class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    // 预置的国密 SM2 公钥，用于验证 digest.txt 的签名，杜绝中间人攻击
    private static final String SM2_PUBLIC_KEY = "3059301306072a8648ce3d020106082a811ccf5501822d03420004cb8653184a9b844514e44888cf209985085a2a92b19f8f4d14053b27891149371fc3783c91048c070c61f200de25c106a6cda4feddb05b84ee16cba51d9a6e3a";

    @Value("${netty.server-url:http://localhost:8080}")
    private String serverUrl;

    @Value("${upgrade.channel:stable}")
    private String channel;

    @Autowired
    private NettyClient nettyClient;

    private String currentVersion = "1.0.0"; // 当前客户端版本号
    private boolean isUpdating = false;      // 升级进行锁，防止重入
    private Long scheduledUpgradeTime = null; // 预约更新时间戳

    public UpgradeService() {
        // 尝试从本地加载 version.txt，若无则默认为 1.0.0
        loadLocalVersion();
    }

    private void loadLocalVersion() {
        File versionFile = new File("version.txt");
        if (versionFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(versionFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("version")) {
                        String[] parts = line.split("=");
                        if (parts.length > 1) {
                            this.currentVersion = parts[1].trim();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Upgrade] Failed to read version.txt, fallback to 1.0.0. error: {}", e.getMessage());
            }
        }
        log.info("[Upgrade] Local Client Version initialized: {}", this.currentVersion);
    }

    /**
     * 主动触发检查更新命令行调用或由外部调用
     */
    public void checkUpgradeImmediate() {
        log.info("[Upgrade] Explicitly triggering check upgrade...");
        new Thread(this::checkUpgradeInternal).start();
    }

    /**
     * 定时轮询服务端的更新检测接口（每隔 1 小时执行一次）
     */
    @Scheduled(fixedRate = 3600000)
    public void scheduledCheckUpgrade() {
        log.info("[Upgrade] Scheduled task: checking for client updates...");
        checkUpgradeInternal();
    }

    /**
     * 处理来自服务端的消息推送（TCP 升级指令）
     */
    public void handleUpgradeCommand(Object command) {
        log.info("[Upgrade] Server pushed UPGRADE command directly. Checking updates immediately.");
        checkUpgradeImmediate();
    }

    /**
     * 预约在指定的时间进行更新，如 "2026-07-05 02:00:00"
     */
    public void scheduleUpgrade(long epochMillis) {
        this.scheduledUpgradeTime = epochMillis;
        log.info("[Upgrade] Scheduled update time configured to: {} (in {} seconds)", 
                new Date(epochMillis), (epochMillis - System.currentTimeMillis()) / 1000);
        
        // 启动预约等待监控线程
        new Thread(this::waitAndExecuteUpgrade).start();
    }

    private synchronized void checkUpgradeInternal() {
        if (isUpdating) {
            log.info("[Upgrade] Upgrade is already in progress, skipping checks.");
            return;
        }

        try {
            // 1. 上报客户端版本、平台、Channel 等信息
            String os = System.getProperty("os.name").toLowerCase();
            String platform = os.contains("win") ? "windows" : (os.contains("mac") ? "macos" : "linux");
            String checkUrl = serverUrl + "/upgrade/check?version=" + currentVersion + "&platform=" + platform + "&channel=" + channel;
            
            log.info("[Upgrade] Sending check request to Server: {}", checkUrl);
            String response = HttpUtil.get(checkUrl, 5000);
            
            if (response == null || response.trim().isEmpty()) {
                log.warn("[Upgrade] Empty response from server. Check aborted.");
                return;
            }

            JSONObject json = JSONUtil.parseObj(response);
            boolean updateAvailable = json.getBool("updateAvailable", false);
            String latestVersion = json.getStr("version");
            String downloadUrl = json.getStr("downloadUrl");
            String strategy = json.getStr("strategy", "AUTO_DOWNLOAD_MANUAL_UPDATE");
            boolean force = json.getBool("force", false);

            if (!updateAvailable) {
                log.info("[Upgrade] Client is up-to-date. (Current: {}, Latest: {})", currentVersion, latestVersion);
                return;
            }

            log.info("[Upgrade] New version available! Latest: {}, Strategy: {}, Force: {}", latestVersion, strategy, force);
            isUpdating = true;

            // 2. 根据策略执行下载
            if ("AUTO_UPDATE".equalsIgnoreCase(strategy) || "AUTO_DOWNLOAD_MANUAL_UPDATE".equalsIgnoreCase(strategy) || force) {
                log.info("[Upgrade] Strategy allows auto-download. Starting download process...");
                new Thread(() -> {
                    try {
                        boolean downloadSuccess = executeDownload(downloadUrl);
                        if (downloadSuccess) {
                            if ("AUTO_UPDATE".equalsIgnoreCase(strategy) || force) {
                                log.info("[Upgrade] AUTO_UPDATE strategy active: executing immediate upgrade.");
                                performRestartAndSwap();
                            } else {
                                log.warn("[Upgrade] =========================================================");
                                log.warn("[Upgrade] ALERT: Client update files downloaded successfully to temp_upgrade!");
                                log.warn("[Upgrade] Please call scheduleUpgrade() to apply the update.");
                                log.warn("[Upgrade] =========================================================");
                                // 默认测试：预约 10 秒后自动更新
                                scheduleUpgrade(System.currentTimeMillis() + 10000);
                            }
                        }
                    } finally {
                        isUpdating = false;
                    }
                }).start();
            } else {
                log.warn("[Upgrade] MANUAL strategy active. Waiting for user to manually trigger download.");
                isUpdating = false;
            }

        } catch (Exception e) {
            log.error("[Upgrade] Upgrade check failed due to exception. Falling back to local run. Error: {}", e.getMessage());
            isUpdating = false;
        }
    }

    private boolean executeDownload(String digestUrl) {
        try {
            File tempDir = new File("temp_upgrade");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // 1. 下载 digest.txt 到暂存区
            log.info("[Upgrade] Downloading digest.txt from: {}", digestUrl);
            File digestFile = new File(tempDir, "digest.txt");
            downloadWithRetry(digestUrl, digestFile);

            // 2. 校验 digest.txt 的签名 (Getdown 安全防御)
            log.info("[Upgrade] Verifying digest.txt signature using SM2...");
            verifyDigestSignature(digestFile);
            log.info("[Upgrade] SM2 signature verified successfully.");

            // 3. 解析 digest.txt 中的文件 SM3 列表
            Map<String, String> remoteFiles = parseDigestFile(digestFile);
            String appbase = digestUrl.substring(0, digestUrl.lastIndexOf('/') + 1);

            // 4. 增量比对，按需下载
            for (Map.Entry<String, String> entry : remoteFiles.entrySet()) {
                String relativePath = entry.getKey();
                String expectedSM3 = entry.getValue();

                if ("digest.txt".equals(relativePath)) {
                    continue; // 摘要本身无需再次下载
                }

                File localFile = new File(relativePath);
                boolean isMatch = false;
                if (localFile.exists()) {
                    String localSM3 = SmUtil.sm3(localFile);
                    if (localSM3.equalsIgnoreCase(expectedSM3)) {
                        isMatch = true;
                    }
                }

                if (isMatch) {
                    log.info("[Upgrade] [Incremental] File matches, skipping download: {}", relativePath);
                } else {
                    log.info("[Upgrade] [Incremental] File is missing or modified: {}", relativePath);
                    File tempTarget = new File(tempDir, relativePath);
                    String fileUrl = appbase + relativePath;

                    // 5. 进行断点续传下载，重试最多 3 次
                    downloadWithResumeAndRetry(fileUrl, tempTarget, expectedSM3);
                }
            }

            log.info("[Upgrade] All update files downloaded and verified successfully in temp_upgrade/.");
            return true;
        } catch (Exception e) {
            log.error("[Upgrade] Download execution failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private void verifyDigestSignature(File digestFile) throws Exception {
        List<String> lines = FileUtil.readUtf8Lines(digestFile);
        if (lines.isEmpty()) {
            throw new IOException("digest.txt is empty");
        }

        // 提取最后一行签名值
        String lastLine = lines.get(lines.size() - 1).trim();
        if (!lastLine.startsWith("signature =")) {
            throw new SecurityException("No signature found in digest.txt!");
        }
        String signatureHex = lastLine.split("=")[1].trim();

        // 重新拼接前文内容以计算签名校验
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() - 1; i++) {
            sb.append(lines.get(i)).append("\n");
        }
        String content = sb.toString();

        SM2 sm2 = SmUtil.sm2(null, SM2_PUBLIC_KEY);
        // 使用公钥校验内容哈希
        boolean verified = sm2.verifyHex(HexUtil.encodeHexStr(content.getBytes(StandardCharsets.UTF_8)), signatureHex);
        if (!verified) {
            throw new SecurityException("SM2 Signature verification failed! The digest file may have been tampered with.");
        }
    }

    private Map<String, String> parseDigestFile(File digestFile) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(digestFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq != -1) {
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    if (!"signature".equals(key)) {
                        map.put(key, val);
                    }
                }
            }
        }
        return map;
    }

    private void downloadWithRetry(String urlStr, File targetFile) throws IOException {
        int attempts = 3;
        while (attempts > 0) {
            try {
                HttpUtil.downloadFile(urlStr, targetFile, 10000);
                return;
            } catch (Exception e) {
                attempts--;
                log.warn("[Upgrade] Download attempt failed for {}, retries remaining: {}. error: {}", urlStr, attempts, e.getMessage());
                if (attempts == 0) {
                    throw new IOException("Failed to download file after 3 attempts: " + urlStr);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void downloadWithResumeAndRetry(String urlStr, File targetFile, String expectedSM3) throws Exception {
        File partFile = new File(targetFile.getAbsolutePath() + ".part");
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            attempt++;
            try {
                long existingLength = partFile.exists() ? partFile.length() : 0;
                log.info("[Upgrade] Downloading: {} (Attempt {}/3, Resuming from: {} bytes)", urlStr, attempt, existingLength);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                if (existingLength > 0) {
                    // 注入 Range 头实现 HTTP 续传
                    conn.setRequestProperty("Range", "bytes=" + existingLength + "-");
                }

                int code = conn.getResponseCode();
                // 200 = OK, 206 = Partial Content
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    File parent = partFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    boolean append = (code == HttpURLConnection.HTTP_PARTIAL && existingLength > 0);
                    try (InputStream is = conn.getInputStream();
                         RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                        
                        if (append) {
                            raf.seek(existingLength);
                        } else {
                            raf.setLength(0);
                        }

                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            raf.write(buffer, 0, len);
                        }
                    }
                    
                    // 重命名为目标文件
                    if (targetFile.exists()) {
                        targetFile.delete();
                    }
                    if (partFile.renameTo(targetFile)) {
                        // 计算并校验 SM3
                        String actualSM3 = SmUtil.sm3(targetFile);
                        if (actualSM3.equalsIgnoreCase(expectedSM3)) {
                            log.info("[Upgrade] Successfully downloaded and verified: {}", targetFile.getName());
                            return; // 校验成功退出
                        } else {
                            log.error("[Upgrade] SM3 verification failed for downloaded file: {}. (Expected: {}, Actual: {})", 
                                    targetFile.getName(), expectedSM3, actualSM3);
                            targetFile.delete();
                            throw new SecurityException("Downloaded file checksum mismatch: " + targetFile.getName());
                        }
                    } else {
                        throw new IOException("Failed to rename temporary file to: " + targetFile.getAbsolutePath());
                    }
                } else {
                    throw new IOException("Server returned HTTP response code: " + code);
                }

            } catch (Exception e) {
                log.warn("[Upgrade] Download execution exception on attempt {}/3 for {}. Error: {}", attempt, targetFile.getName(), e.getMessage());
                if (attempt >= maxRetries) {
                    throw new IOException("Failed to download and verify file " + targetFile.getName() + " after 3 attempts.", e);
                }
                TimeUnit.MILLISECONDS.sleep(1000); // 间隔1秒重试
            }
        }
    }

    private void waitAndExecuteUpgrade() {
        while (scheduledUpgradeTime != null) {
            long remaining = scheduledUpgradeTime - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("[Upgrade] Scheduled upgrade time reached! Triggering client graceful shutdown...");
                performRestartAndSwap();
                break;
            }
            try {
                // 每秒检查一次
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void performRestartAndSwap() {
        log.warn("[Upgrade] =========================================================");
        log.warn("[Upgrade] EXECUTING SYSTEM UPDATE & GRACEFUL SHUTDOWN IN PROGRESS");
        log.warn("[Upgrade] =========================================================");

        try {
            // 1. 优雅停止 Netty 客户端的网络连接及资源
            nettyClient.stop();
            log.info("[Upgrade] Netty connection closed gracefully.");

            // 2. 延迟 1 秒，等待所有后台任务清理完毕
            Thread.sleep(1000);

            // 3. 释放单例锁定文件（显式调用，防止操作系统释放不及时）
            // 退出码 10 会通知父进程 UpgradeAgent 进行 Jar 包替换并自动拉起重启
            log.warn("[Upgrade] Client process is exiting with status code 10. Handover to UpgradeAgent watchdog.");
            System.exit(10);

        } catch (Exception e) {
            log.error("[Upgrade] Failed to gracefully shutdown: {}", e.getMessage(), e);
            System.exit(10); // 异常时强制采用更新码退出
        }
    }
}
