package com.example.netty.upgrade;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.threerings.getdown.launcher.GetdownApp;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Properties;

public class CustomLauncher {

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("Starting Custom Getdown Launcher...");
        System.out.println("=========================================");

        // Define the target run directory where the client will be installed/updated
        File appDir = new File("client-run-dir");
        if (!appDir.exists()) {
            appDir.mkdirs();
        }

        // Resolve credentials
        String accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        String accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");

        if (accessKeyId == null || accessKeyId.trim().isEmpty() ||
            accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            accessKeyId = System.getProperty("oss.accessKeyId");
            accessKeySecret = System.getProperty("oss.accessKeySecret");
        }

        if (accessKeyId == null || accessKeyId.trim().isEmpty() ||
            accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            // Try resolving from local.properties in current dir or parent dirs
            File baseDir = new File(System.getProperty("user.dir"));
            File localPropsFile = findLocalProperties(baseDir);
            if (localPropsFile != null) {
                try (InputStream is = new FileInputStream(localPropsFile)) {
                    Properties props = new Properties();
                    props.load(is);
                    accessKeyId = props.getProperty("oss.accessKeyId");
                    accessKeySecret = props.getProperty("oss.accessKeySecret");
                } catch (IOException e) {
                    System.err.println("Failed to read local.properties: " + e.getMessage());
                }
            }
        }

        if (accessKeyId == null || accessKeyId.trim().isEmpty() ||
            accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            System.err.println("ERROR: Aliyun OSS credentials are not configured!");
            System.err.println("Please set OSS_ACCESS_KEY_ID & OSS_ACCESS_KEY_SECRET env vars");
            System.err.println("or add oss.accessKeyId & oss.accessKeySecret in local.properties.");
            System.exit(1);
        }

        String endpoint = "https://oss-cn-beijing.aliyuncs.com";
        String bucketName = "tomin";

        System.out.println("Initializing Aliyun OSS Client with credentials...");
        final OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        // Start Local HTTP server proxy on a random free port
        final HttpServer server;
        final int port;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            
            server.createContext("/upgrade-dir/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String path = exchange.getRequestURI().getPath();
                    // Strip leading slash if present
                    String ossKey = path.startsWith("/") ? path.substring(1) : path;
                    
                    try {
                        if (ossKey.endsWith("getdown.txt")) {
                            System.out.println("[LocalProxy] Fetching getdown.txt from OSS and rewriting appbase...");
                            OSSObject ossObject = ossClient.getObject(bucketName, ossKey);
                            String originalContent;
                            try (InputStream is = ossObject.getObjectContent();
                                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[4096];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    bos.write(buffer, 0, len);
                                }
                                originalContent = bos.toString("UTF-8");
                            }
                            
                            String rewrittenContent = rewriteAppBase(originalContent, port);
                            byte[] contentBytes = rewrittenContent.getBytes("UTF-8");
                            
                            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                            exchange.sendResponseHeaders(200, contentBytes.length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(contentBytes);
                                os.flush();
                            }
                            System.out.println("[LocalProxy] Successfully served rewritten getdown.txt");
                            return;
                        }
                        
                        // Check if file exists on OSS
                        if (!ossClient.doesObjectExist(bucketName, ossKey)) {
                            System.err.println("[LocalProxy] File not found in OSS: " + ossKey);
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        
                        OSSObject ossObject = ossClient.getObject(bucketName, ossKey);
                        long contentLength = ossObject.getObjectMetadata().getContentLength();
                        String contentType = ossObject.getObjectMetadata().getContentType();
                        
                        if (contentType != null) {
                            exchange.getResponseHeaders().set("Content-Type", contentType);
                        }
                        
                        exchange.sendResponseHeaders(200, contentLength > 0 ? contentLength : 0);
                        try (InputStream is = ossObject.getObjectContent();
                             OutputStream os = exchange.getResponseBody()) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                            os.flush();
                        }
                        System.out.println("[LocalProxy] Successfully served: " + ossKey + " (" + contentLength + " bytes)");
                    } catch (Exception e) {
                        System.err.println("[LocalProxy] Error serving path: " + path + ", error: " + e.getMessage());
                        e.printStackTrace();
                        exchange.sendResponseHeaders(500, -1);
                    }
                }
            });
            
            // Set executor to null (default) but run in background
            server.setExecutor(null);
            server.start();
            System.out.println("Local HTTP proxy started at http://127.0.0.1:" + port + "/upgrade-dir/");
        } catch (IOException e) {
            System.err.println("Failed to start local HTTP proxy server: " + e.getMessage());
            ossClient.shutdown();
            System.exit(1);
            return;
        }

        // Add Shutdown Hook to stop the proxy server and close OSS client
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down local proxy server and OSS client...");
            if (server != null) {
                server.stop(0);
            }
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }));

        // Write/update the local getdown.txt in client-run-dir to point to this port
        File getdownTxt = new File(appDir, "getdown.txt");
        System.out.println("Writing local bootstrap getdown.txt in " + appDir.getAbsolutePath() + " targeting port: " + port);
        try (FileWriter writer = new FileWriter(getdownTxt)) {
            writer.write("appbase = http://127.0.0.1:" + port + "/upgrade-dir/\n");
            writer.write("class = com.example.netty.client.NettyClientApplication\n");
            writer.write("code = netty-client.jar\n");
            writer.write("jvmarg = -Dfile.encoding=UTF-8\n");
        } catch (IOException e) {
            System.err.println("Failed to bootstrap getdown.txt: " + e.getMessage());
            System.exit(1);
        }

        // Set Getdown configuration to run headlessly (no Swing UI)
        System.setProperty("no_gui", "true");

        try {
            System.out.println("Invoking GetdownApp.main in app dir: " + appDir.getAbsolutePath());
            // Invoke GetdownApp.main with the target directory as the argument
            GetdownApp.main(new String[]{ appDir.getPath() });
        } catch (Exception e) {
            System.err.println("Error executing Getdown launcher: " + e.getMessage());
            e.printStackTrace();
        }
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

    private static String rewriteAppBase(String original, int port) {
        String[] lines = original.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().startsWith("appbase")) {
                sb.append("appbase = http://127.0.0.1:").append(port).append("/upgrade-dir/\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
