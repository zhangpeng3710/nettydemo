package com.example.netty.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@SpringBootApplication
public class NettyServerApplication {

    public static void main(String[] args) {
        // Resolve OSS credentials from local.properties if not set in environment/system properties
        if (System.getenv("OSS_ACCESS_KEY_ID") == null && System.getProperty("oss.accessKeyId") == null) {
            File baseDir = new File(System.getProperty("user.dir"));
            File localPropsFile = findLocalProperties(baseDir);
            if (localPropsFile != null) {
                try (InputStream is = new FileInputStream(localPropsFile)) {
                    Properties props = new Properties();
                    props.load(is);
                    String ak = props.getProperty("oss.accessKeyId");
                    String sk = props.getProperty("oss.accessKeySecret");
                    if (ak != null && !ak.trim().isEmpty()) {
                        System.setProperty("oss.accessKeyId", ak.trim());
                    }
                    if (sk != null && !sk.trim().isEmpty()) {
                        System.setProperty("oss.accessKeySecret", sk.trim());
                    }
                    System.out.println("Loaded OSS credentials from: " + localPropsFile.getAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Failed to load local.properties on server startup: " + e.getMessage());
                }
            }
        }
        SpringApplication.run(NettyServerApplication.class, args);
    }

    private static File findLocalProperties(File currentDir) {
        File dir = currentDir;
        while (dir != null) {
            File f = new File(dir, "local.properties");
            if (f.exists()) {
                return f;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
