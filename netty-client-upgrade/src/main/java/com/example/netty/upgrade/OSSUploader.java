package com.example.netty.upgrade;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class OSSUploader {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: OSSUploader <directory-to-upload>");
            System.exit(1);
        }

        String dirPath = args[0];
        File uploadDir = new File(dirPath);
        if (!uploadDir.exists() || !uploadDir.isDirectory()) {
            System.err.println("Invalid directory: " + dirPath);
            System.exit(1);
        }

        // Get credentials from environment variable first, then system property
        String accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        if (accessKeyId == null || accessKeyId.trim().isEmpty()) {
            accessKeyId = System.getProperty("oss.accessKeyId");
        }

        String accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");
        if (accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            accessKeySecret = System.getProperty("oss.accessKeySecret");
        }

        if (accessKeyId == null || accessKeyId.trim().isEmpty() ||
            accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            System.out.println("=================================================================");
            System.out.println("WARNING: OSS credentials (OSS_ACCESS_KEY_ID & OSS_ACCESS_KEY_SECRET) not set!");
            System.out.println("Skipping OSS upload. Please set them as environment variables");
            System.out.println("or system properties (-Doss.accessKeyId / -Doss.accessKeySecret).");
            System.out.println("=================================================================");
            return;
        }

        String endpoint = "https://oss-cn-beijing.aliyuncs.com";
        String bucketName = "tomin";
        String targetPrefix = "upgrade-dir/";

        System.out.println("Initializing OSS Client for bucket: " + bucketName + " (endpoint: " + endpoint + ")...");
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            List<File> filesToUpload = new ArrayList<>();
            findFiles(uploadDir, filesToUpload);

            System.out.println("Found " + filesToUpload.size() + " files to upload in " + uploadDir.getAbsolutePath());

            for (File file : filesToUpload) {
                // Calculate the relative path from the uploadDir
                String relativePath = uploadDir.toURI().relativize(file.toURI()).getPath();
                // Replace backslashes just in case on Windows
                relativePath = relativePath.replace('\\', '/');
                String objectKey = targetPrefix + relativePath;

                System.out.println("Uploading " + file.getName() + " -> " + objectKey + " (" + file.length() + " bytes)...");
                PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, objectKey, file);
                ossClient.putObject(putObjectRequest);
                System.out.println("Successfully uploaded: " + objectKey);
            }
            System.out.println("All files uploaded successfully to OSS!");
        } catch (Exception e) {
            System.err.println("OSS upload failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            ossClient.shutdown();
        }
    }

    private static void findFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                findFiles(f, files);
            } else {
                files.add(f);
            }
        }
    }
}
