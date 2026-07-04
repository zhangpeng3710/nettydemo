package com.example.netty.client.tools;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.SM2;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建期摘要生成与国密签名工具，用于对客户端版本文件进行打包签名。
 */
public class Digester {

    // 预置的 SM2 私钥（用于生成签名，公开给 build 阶段）
    private static final String SM2_PRIVATE_KEY = "308193020100301306072a8648ce3d020106082a811ccf5501822d0479307702010104203d8f84101d432769933687ac491bb5047b06788af9bff457f3d05f7f309ebc18a00a06082a811ccf5501822da14403420004cb8653184a9b844514e44888cf209985085a2a92b19f8f4d14053b27891149371fc3783c91048c070c61f200de25c106a6cda4feddb05b84ee16cba51d9a6e3a";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java com.example.netty.client.tools.Digester <app-dir>");
            System.exit(1);
        }

        File appDir = new File(args[0]);
        if (!appDir.exists() || !appDir.isDirectory()) {
            System.err.println("[Digester] Invalid app directory: " + appDir.getAbsolutePath());
            System.exit(1);
        }

        System.out.println("[Digester] Starting SM3 digestion and SM2 signing on: " + appDir.getAbsolutePath());
        try {
            generateDigests(appDir);
            System.out.println("[Digester] Digest file digest.txt generated and signed successfully.");
        } catch (Exception e) {
            System.err.println("[Digester] Error occurred: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void generateDigests(File appDir) throws Exception {
        File getdownTxt = new File(appDir, "getdown.txt");
        if (!getdownTxt.exists()) {
            throw new FileNotFoundException("getdown.txt not found in " + appDir.getAbsolutePath());
        }

        // 1. 读取 getdown.txt，解析所有 code 和 resource 声明的文件路径
        List<String> filesToHash = new ArrayList<>();
        filesToHash.add("getdown.txt"); // getdown.txt 自身需要被校验

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(getdownTxt), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx != -1) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    if ("code".equals(key) || "resource".equals(key)) {
                        filesToHash.add(val);
                    }
                }
            }
        }

        // 2. 依次计算每个文件的 SM3 摘要值，并按顺序拼接
        StringBuilder sb = new StringBuilder();
        for (String relativePath : filesToHash) {
            String normalizedPath = relativePath.replace('\\', '/');
            File file = new File(appDir, normalizedPath);
            if (!file.exists()) {
                System.out.println("[Digester] Warning: File in getdown.txt does not exist: " + file.getAbsolutePath());
                continue;
            }
            String fileSM3 = SmUtil.sm3(file);
            sb.append(normalizedPath).append(" = ").append(fileSM3).append("\n");
            System.out.println("[Digester] Digested: " + normalizedPath + " -> " + fileSM3);
        }

        // 3. 计算 digest.txt 的自哈希值（代表除了最后两行外，整个文件的完整完整性）
        String contentSoFar = sb.toString();
        byte[] contentBytes = contentSoFar.getBytes(StandardCharsets.UTF_8);
        String digestSelfSM3 = SmUtil.sm3(new ByteArrayInputStream(contentBytes));
        sb.append("digest.txt = ").append(digestSelfSM3).append("\n");
        System.out.println("[Digester] Self digest: digest.txt -> " + digestSelfSM3);

        // 4. 对以上所有的拼接内容（包含每一行的摘要以及自哈希）进行 SM2 签名
        String signedContent = sb.toString();
        SM2 sm2 = SmUtil.sm2(SM2_PRIVATE_KEY, null);
        // 使用私钥签名
        String signatureHex = sm2.signHex(HexUtil.encodeHexStr(signedContent.getBytes(StandardCharsets.UTF_8)));
        sb.append("signature = ").append(signatureHex).append("\n");
        System.out.println("[Digester] Generated SM2 Signature: " + signatureHex);

        // 5. 最终持久化写入到 digest.txt
        File digestFile = new File(appDir, "digest.txt");
        try (FileOutputStream fos = new FileOutputStream(digestFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        // 6. 生成轻量版本标识元数据 version.txt (内容包含版本号及最终 digest.txt 的自摘要，用于极轻量化对比)
        String clientVersion = "1.0.1"; // 默认测试版本号
        // 可从 getdown.txt 或者外部参数读取，暂定读取 getdown.txt 自定义字段，如未找到默认为 1.0.1
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(getdownTxt), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("version")) {
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        clientVersion = parts[1].trim();
                    }
                }
            }
        }
        
        File versionFile = new File(appDir, "version.txt");
        try (FileWriter fw = new FileWriter(versionFile)) {
            fw.write("version = " + clientVersion + "\n");
            fw.write("digest.sm3 = " + digestSelfSM3 + "\n");
        }
        System.out.println("[Digester] Generated version.txt: version=" + clientVersion + ", digest.sm3=" + digestSelfSM3);
    }
}
