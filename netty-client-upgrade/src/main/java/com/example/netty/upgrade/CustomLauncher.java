package com.example.netty.upgrade;

import com.threerings.getdown.launcher.GetdownApp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

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

        // Bootstrap getdown.txt if it doesn't exist in the target directory
        File getdownTxt = new File(appDir, "getdown.txt");
        if (!getdownTxt.exists()) {
            System.out.println("Bootstrapping getdown.txt in " + appDir.getAbsolutePath());
            try (FileWriter writer = new FileWriter(getdownTxt)) {
                writer.write("appbase = http://localhost:8080/upgrade\n");
                writer.write("class = org.springframework.boot.loader.JarLauncher\n");
                writer.write("code = netty-client.jar\n");
                writer.write("jvmarg = -Dfile.encoding=UTF-8\n");
            } catch (IOException e) {
                System.err.println("Failed to bootstrap getdown.txt: " + e.getMessage());
                System.exit(1);
            }
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
}
