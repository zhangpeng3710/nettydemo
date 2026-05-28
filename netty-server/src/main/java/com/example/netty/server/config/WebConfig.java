package com.example.netty.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${netty.upgrade-dir}")
    private String upgradeDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Ensure the upgrade directory exists
        File dir = new File(upgradeDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Map http://localhost:8080/upgrade/** to the absolute file path of upgradeDir
        String pathPattern = "/upgrade/**";
        String resourceLocation = "file:" + dir.getAbsolutePath() + "/";
        
        registry.addResourceHandler(pathPattern)
                .addResourceLocations(resourceLocation);
    }
}
