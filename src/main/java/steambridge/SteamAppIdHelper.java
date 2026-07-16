/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Utilities for Steam setup before SteamAPI.init().
 *
 * <p>Ensures {@code steam_appid.txt} exists with value "480" (Spacewar).
 * Can also launch {@code steam://run/480} to bring Steam online.</p>
 *
 * @author Ragalikx (https://github.com/Ragalikx/steam-bridge-mc)
 */
public final class SteamAppIdHelper {

    /**
     * Spacewar - Valve's free public test App ID. Hardcoded on purpose and NOT configurable:
     * pointing the mod at a real game's App ID (especially one with VAC/EAC) would ban the
     * user's account. Being a numeric literal also removes any command-injection surface from
     * the steam://run/&lt;id&gt; launch path.
     */
    public static final String APP_ID = "480";

    /** Returns the fixed Spacewar App ID ("480"). */
    public static String getAppId() {
        return APP_ID;
    }

    private SteamAppIdHelper() {}


    /**
     * Writes {@code steam_appid.txt} into {@code gameDir} if it does not already exist
     * or contains a different value.
     *
     * @param gameDir the .minecraft directory (Minecraft.getMinecraft().gameDir)
     */
    public static void ensureAppId(File gameDir) {
        String appId = getAppId();

        // Steam native library ALWAYS reads from the process current working directory (CWD)
        File cwdTarget = new File("steam_appid.txt");
        File gameDirTarget = new File(gameDir, "steam_appid.txt");

        writeAppIdFile(cwdTarget, appId);
        if (!cwdTarget.getAbsolutePath().equals(gameDirTarget.getAbsolutePath())) {
            writeAppIdFile(gameDirTarget, appId);
        }
    }

    private static void writeAppIdFile(File target, String appId) {
        try {
            if (target.exists()) {
                String current = new String(java.nio.file.Files.readAllBytes(target.toPath())).trim();
                if (appId.equals(current)) {
                    return;
                }
                SteamBridgeMod.LOG.warn("[SteamAppId] {} contains '{}', overwriting with '{}'",
                        target.getAbsolutePath(), current, appId);
            }
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(appId);
            }
            SteamBridgeMod.LOG.info("[SteamAppId] Created {} with appid={}", target.getAbsolutePath(), appId);
        } catch (IOException e) {
            SteamBridgeMod.LOG.error("[SteamAppId] Could not write {}: {}", target.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Tries to launch Steam via {@code steam://run/<appId>}.
     * Best-effort - silently ignores errors (Steam may already be running).
     */
    public static void launchSteam() {
        // appId is guaranteed numeric by getAppId(); the launch path below also never goes
        // through a command shell (no "cmd /c"), so the URL cannot be interpreted as a command.
        String appId = getAppId();
        String uri = "steam://run/" + appId;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                // explorer.exe resolves the protocol handler directly, without shell parsing.
                pb = new ProcessBuilder("explorer.exe", uri);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", uri);
            } else {
                pb = new ProcessBuilder("xdg-open", uri);
            }
            pb.start();
            SteamBridgeMod.LOG.info("[SteamAppId] Launched {}", uri);
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[SteamAppId] Could not launch Steam: {}", e.getMessage());
        }
    }
}



