package com.example.netty.upgrade;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 客户端守护进程（Watchdog Daemon），负责监控客户端进程、崩溃自愈、接收更新信号并安全覆盖和重启。
 */
public class UpgradeAgent {

    private static final String LOCK_FILE_NAME = "netty-client.lock";
    private static final String GETDOWN_TXT = "getdown.txt";
    private static final String TEMP_UPGRADE_DIR = "temp_upgrade";

    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println("Starting UpgradeAgent Watchdog Daemon...");
        System.out.println("=========================================================");

        // 默认工作运行目录为 client-run-dir
        File appDir = new File("client-run-dir");
        if (args.length > 0) {
            appDir = new File(args[0]);
        }
        if (!appDir.exists()) {
            appDir.mkdirs();
        }

        System.out.println("[Agent] App Directory: " + appDir.getAbsolutePath());

        // 守护进程的监控循环
        UpgradeAgent agent = new UpgradeAgent();
        agent.runWatchdog(appDir);
    }

    public void runWatchdog(File appDir) {
        while (true) {
            System.out.println("[Agent] Spawning netty-client process...");
            Process process = launchClient(appDir);
            if (process == null) {
                System.err.println("[Agent] Failed to launch client. Retrying in 10 seconds...");
                sleep(10000);
                continue;
            }

            try {
                // 阻塞挂起，等待子进程退出并捕获退出码
                int exitCode = process.waitFor();
                System.out.println("[Agent] Netty Client exited with code: " + exitCode);

                if (exitCode == 10) {
                    // 退出码为 10，代表客户端发起更新请求
                    System.out.println("[Agent] Exit code 10 detected: starting update execution...");
                    boolean updateSuccess = executeUpdateSwap(appDir);
                    if (updateSuccess) {
                        System.out.println("[Agent] Update applied successfully. Restarting client immediately.");
                    } else {
                        System.err.println("[Agent] Update failed. Reverting and restarting existing client.");
                    }
                } else if (exitCode == 0) {
                    // 退出码为 0，代表客户端是正常关机，守护进程同步退场
                    System.out.println("[Agent] Client shutdown normally (exit 0). Watchdog exiting.");
                    System.exit(0);
                } else {
                    // 退出码非 0 且非 10，代表客户端崩溃，挂起 5 秒后自动重新启动（崩溃自愈）
                    System.out.println("[Agent] Client crashed. Automatic recovery in 5 seconds...");
                    sleep(5000);
                }
            } catch (InterruptedException e) {
                System.err.println("[Agent] Watchdog thread interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private Process launchClient(File appDir) {
        File getdownTxt = new File(appDir, GETDOWN_TXT);
        if (!getdownTxt.exists()) {
            // 如果不存在 getdown.txt，尝试等待或报错
            System.err.println("[Agent] Cannot find getdown.txt in " + appDir.getAbsolutePath());
            return null;
        }

        try {
            // 解析 getdown.txt 配置
            Map<String, List<String>> config = parseGetdownConfig(getdownTxt);
            List<String> mainClassList = config.get("class");
            if (mainClassList == null || mainClassList.isEmpty()) {
                throw new IOException("Missing 'class = <main-class>' in getdown.txt");
            }
            String mainClass = mainClassList.get(0);

            // 构建 Classpath
            List<String> codeEntries = config.get("code");
            if (codeEntries == null || codeEntries.isEmpty()) {
                throw new IOException("Missing 'code = <classpath-jar>' in getdown.txt");
            }

            StringBuilder classpath = new StringBuilder();
            String pathSeparator = File.pathSeparator;
            for (int i = 0; i < codeEntries.size(); i++) {
                File jar = new File(appDir, codeEntries.get(i));
                classpath.append(jar.getAbsolutePath());
                if (i < codeEntries.size() - 1) {
                    classpath.append(pathSeparator);
                }
            }

            // 构造 JVM 运行命令
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.add("-cp");
            command.add(classpath.toString());

            // 注入 JVM 运行参数
            List<String> jvmArgs = config.get("jvmarg");
            if (jvmArgs != null) {
                command.addAll(jvmArgs);
            }

            // 指定主类
            command.add(mainClass);

            System.out.println("[Agent] Command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(appDir);
            // 继承标准 IO，使客户端的控制台日志能够直接在守护控制台中显示
            pb.inheritIO();
            return pb.start();

        } catch (Exception e) {
            System.err.println("[Agent] Failed to prepare client launch: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private boolean executeUpdateSwap(File appDir) {
        File tempDir = new File(appDir, TEMP_UPGRADE_DIR);
        if (!tempDir.exists() || !tempDir.isDirectory()) {
            System.err.println("[Agent] Update folder temp_upgrade/ does not exist. Update aborted.");
            return false;
        }

        // 1. 进行文件锁独占检测（Getdown 核心防锁定覆盖机制）
        File lockFile = new File(appDir, LOCK_FILE_NAME);
        boolean isLockReleased = waitAndAcquireLock(lockFile);
        if (!isLockReleased) {
            System.err.println("[Agent] Timeout waiting for client file locks to release. Update aborted.");
            return false;
        }

        try {
            System.out.println("[Agent] Safe to update. Swapping files from temp_upgrade/...");

            // 2. 递归复制并覆盖 temp_upgrade 内的所有文件到主运行目录
            List<File> filesToCopy = new ArrayList<>();
            findFilesRecursively(tempDir, filesToCopy);

            for (File src : filesToCopy) {
                // 计算相对路径
                String relPath = tempDir.toURI().relativize(src.toURI()).getPath();
                File dest = new File(appDir, relPath);

                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                // 复制文件
                copyFile(src, dest);
                System.out.println("[Agent] Copied: " + relPath);
            }

            // 3. 本地无用依赖清理（参考 Getdown 垃圾清理设计）
            // 读取更新后的 getdown.txt 重新整理合法类路径
            File newGetdown = new File(appDir, GETDOWN_TXT);
            if (newGetdown.exists()) {
                Map<String, List<String>> newConfig = parseGetdownConfig(newGetdown);
                List<String> validCodes = newConfig.get("code");
                if (validCodes != null) {
                    Set<String> validFiles = new HashSet<>(validCodes);
                    validFiles.add(GETDOWN_TXT);
                    validFiles.add("digest.txt");
                    validFiles.add("version.txt");
                    validFiles.add(LOCK_FILE_NAME);

                    cleanupObsoleteFiles(appDir, validFiles);
                }
            }

            // 4. 清理暂存区
            deleteDirectory(tempDir);
            System.out.println("[Agent] Cleaned up temp_upgrade/ directory.");
            return true;

        } catch (Exception e) {
            System.err.println("[Agent] Update swap failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean waitAndAcquireLock(File lockFile) {
        int maxRetries = 10;
        int delay = 500;
        System.out.println("[Agent] Waiting for client file lock release...");

        for (int i = 0; i < maxRetries; i++) {
            if (!lockFile.exists()) {
                // 如果锁文件根本不存在，说明已经被客户端删除了，或者没有加锁
                return true;
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.tryLock()) {
                
                if (lock != null) {
                    // 能成功加独占锁，说明前进程已彻底死亡
                    lock.release();
                    return true;
                }
            } catch (Exception e) {
                // 仍被锁定
            }
            sleep(delay);
        }
        return false;
    }

    private Map<String, List<String>> parseGetdownConfig(File file) throws IOException {
        Map<String, List<String>> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
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
                    map.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
                }
            }
        }
        return map;
    }

    private void findFilesRecursively(File dir, List<File> result) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                findFilesRecursively(f, result);
            } else {
                result.add(f);
            }
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (InputStream is = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
    }

    private void cleanupObsoleteFiles(File appDir, Set<String> validFiles) {
        File libDir = new File(appDir, "lib");
        if (!libDir.exists() || !libDir.isDirectory()) {
            return;
        }

        List<File> allFiles = new ArrayList<>();
        findFilesRecursively(libDir, allFiles);

        for (File f : allFiles) {
            String relPath = appDir.toURI().relativize(f.toURI()).getPath();
            relPath = relPath.replace('\\', '/');
            if (relPath.endsWith("/")) {
                relPath = relPath.substring(0, relPath.length() - 1);
            }

            if (!validFiles.contains(relPath)) {
                System.out.println("[Agent] Cleaning obsolete dependency file: " + relPath);
                f.delete();
            }
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
