package com.example.netty.server.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OSSConfig {

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.access-key-id}")
    private String accessKeyId;

    @Value("${oss.access-key-secret}")
    private String accessKeySecret;

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {
        if (accessKeyId == null || accessKeyId.trim().isEmpty() ||
            accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            System.err.println("WARNING: Aliyun OSS credentials are not configured for netty-server.");
            System.err.println("Please configure oss.access-key-id and oss.access-key-secret.");
            throw new IllegalStateException("Aliyun OSS credentials are not configured for netty-server!");
        }
        System.out.println("Initializing Aliyun OSS client bean for endpoint: " + endpoint);
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }
}
