/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** steam_appid.txt (480 / Spacewar) and optional Steam / Spacewar launch helpers. */
public final class SteamAppIdHelper {

    /** Spacewar. Fixed; do not point this at a VAC/EAC game. */
    public static final String APP_ID = "480";
    public static final int APP_ID_INT = 480;

    public static String getAppId() {
        return APP_ID;
    }

    private SteamAppIdHelper() {}

    public static void ensureAppId(File gameDir) {
        String appId = getAppId();

        // Steam reads steam_appid.txt from process CWD.
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
     * Best-effort process probe for the Steam client (not the Steamworks API).
     * Used when {@code SteamAPI.init} fails so we can tell "Steam is off" from
     * "Steam is on but Spacewar/Family View blocked the API".
     */
    public static boolean isSteamClientProcessRunning() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH");
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("pgrep", "-x", "steam_osx");
            } else {
                // Linux: main client is usually "steam"
                pb = new ProcessBuilder("pgrep", "-x", "steam");
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean matched = false;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim().toLowerCase(Locale.ROOT);
                    if (s.isEmpty()) continue;
                    if (os.contains("win")) {
                        // tasklist: "steam.exe ..." or "INFO: No tasks..."
                        if (s.contains("steam.exe")) {
                            matched = true;
                            break;
                        }
                    } else {
                        // pgrep prints PIDs when found
                        if (s.matches("\\d+")) {
                            matched = true;
                            break;
                        }
                    }
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            return matched;
        } catch (Exception e) {
            SteamBridgeMod.LOG.debug("[SteamAppId] Steam process probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Best-effort steam://run/480 (starts Steam if needed, then Spacewar). */
    public static void launchSteam() {
        String appId = getAppId();
        String uri = "steam://run/" + appId;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", uri);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", uri);
            } else {
                pb = new ProcessBuilder("xdg-open", uri);
            }
            pb.start();
            SteamBridgeMod.LOG.info("[SteamAppId] Launching Steam/Spacewar: {}", uri);
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[SteamAppId] Could not launch Steam: {}", e.getMessage());
        }
    }
}
