package com.example.netty.common.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class DynamicPluginLoader {
    private static final Logger log = LoggerFactory.getLogger(DynamicPluginLoader.class);

    public static List<MessagePlugin> loadPluginsFromDir(String dirPath) {
        List<MessagePlugin> loadedPlugins = new ArrayList<>();
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Plugin directory does not exist: {}", dirPath);
            return loadedPlugins;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) {
            return loadedPlugins;
        }

        try {
            List<URL> urls = new ArrayList<>();
            for (File file : files) {
                urls.add(file.toURI().toURL());
                log.info("Found plugin jar: {}", file.getName());
            }

            URLClassLoader classLoader = new URLClassLoader(
                    urls.toArray(new URL[0]),
                    DynamicPluginLoader.class.getClassLoader()
            );

            // Use Java SPI ServiceLoader to discover implementations
            ServiceLoader<MessagePlugin> serviceLoader = ServiceLoader.load(MessagePlugin.class, classLoader);
            for (MessagePlugin plugin : serviceLoader) {
                loadedPlugins.add(plugin);
            }
        } catch (Exception e) {
            log.error("Failed to load dynamic plugins from {}", dirPath, e);
        }

        return loadedPlugins;
    }
}
