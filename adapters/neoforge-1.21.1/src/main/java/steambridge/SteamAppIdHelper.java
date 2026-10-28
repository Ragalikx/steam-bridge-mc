/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/** steam_appid.txt (480 / Spacewar) and optional Steam / Spacewar launch helpers. */
public final class SteamAppIdHelper {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

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
                String current =
                        new String(java.nio.file.Files.readAllBytes(target.toPath())).trim();
                if (appId.equals(current)) {
                    return;
                }
                ProblemLog.warn(
                        SteamBridgeMod.LOG,
                        LOG_LIMITER,
                        "SteamAppIdHelper-warn-1",
                        "[SteamAppId] {} contains '{}', overwriting with '{}'",
                        target.getAbsolutePath(),
                        current,
                        appId);
            }
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(appId);
            }
            SteamBridgeMod.LOG.debug(
                    "[SteamAppId] Created {} with appid={}", target.getAbsolutePath(), appId);
        } catch (IOException e) {
            SteamBridgeMod.LOG.error(
                    "[SteamAppId] Could not write {}: {}",
                    target.getAbsolutePath(),
                    e.getMessage());
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
            SteamBridgeMod.LOG.debug("[SteamAppId] Launching Steam/Spacewar: {}", uri);
        } catch (Exception e) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "SteamAppIdHelper-warn-2",
                    "[SteamAppId] Could not launch Steam: {}",
                    e.getMessage());
        }
    }
}
