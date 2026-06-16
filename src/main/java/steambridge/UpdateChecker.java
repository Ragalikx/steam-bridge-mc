/*
 * Copyright (c) 2019-2026 Ragalikx
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package steambridge;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches version.json from GitHub in a background daemon thread and notifies
 * the player once when they enter a world.
 *
 * Checks two things:
 *   1. Version mismatch  -> "update available" message with download link.
 *   2. Same version but SHA-256 differs -> integrity warning (tampered/corrupted JAR).
 *
 * Disable entirely via {@code checkForUpdates = false} in the mod config.
 */
public final class UpdateChecker {

    private static volatile String  remoteVersion  = null;
    private static volatile String  remoteSha256   = null;
    private static volatile String  downloadUrl    = null;
    private static volatile boolean integrityFail  = false;
    private static final AtomicBoolean checkDone   = new AtomicBoolean(false);

    private UpdateChecker() {}

    /** Called once from mod init on the client side. */
    public static void init() {
        if (!SteamBridgeConfig.checkForUpdates) {
            SteamBridgeMod.LOG.info("[UpdateChecker] Disabled by config — skipping.");
            return;
        }
        MinecraftForge.EVENT_BUS.register(new TickListener());
        Thread t = new Thread(UpdateChecker::runCheck, "SteamBridge-UpdateChecker");
        t.setDaemon(true);
        t.start();
    }

    // --- background check ---------------------------------------------------

    private static void runCheck() {
        try {
            String json = fetchString(BuildInfo.VERSION_URL);
            if (json == null || json.isEmpty()) return;

            String version = extractField(json, "version");
            String sha256  = extractField(json, "sha256");
            String dlUrl   = extractField(json, "download_url");

            if (version == null || version.isEmpty()) {
                SteamBridgeMod.LOG.warn("[UpdateChecker] version.json missing 'version' field.");
                return;
            }

            remoteVersion = version;
            remoteSha256  = sha256;
            downloadUrl   = (dlUrl != null && !dlUrl.isEmpty()) ? dlUrl : BuildInfo.RELEASES;

            SteamBridgeMod.LOG.info("[UpdateChecker] Remote version={}  local={}",
                remoteVersion, BuildInfo.VERSION);

            // Integrity check only makes sense when versions match
            if (BuildInfo.VERSION.equals(version) && sha256 != null && !sha256.isEmpty()) {
                String localSha = computeLocalSha256();
                if (localSha != null && !localSha.equalsIgnoreCase(sha256)) {
                    integrityFail = true;
                    SteamBridgeMod.LOG.warn(
                        "[UpdateChecker] SHA-256 mismatch! expected={} actual={}",
                        sha256, localSha);
                }
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[UpdateChecker] Check failed: {}", e.getMessage());
        } finally {
            checkDone.set(true);
        }
    }

    // --- notification ------------------------------------------------------

    private static void showNotification(Minecraft mc) {
        if (integrityFail) {
            mc.player.sendMessage(new TextComponentString(
                TextFormatting.RED + "[SteamBridge] " +
                TextFormatting.YELLOW + "JAR integrity check failed — " +
                "your mod file may be corrupted or tampered with. " +
                "Re-download from: " + BuildInfo.RELEASES
            ));
            return;
        }

        if (remoteVersion != null && !remoteVersion.equals(BuildInfo.VERSION)) {
            mc.player.sendMessage(new TextComponentString(
                TextFormatting.AQUA   + "[SteamBridge] " +
                TextFormatting.GREEN  + "Update available: v" + remoteVersion +
                TextFormatting.GRAY   + "  (installed: v" + BuildInfo.VERSION + ")" +
                "\n" +
                TextFormatting.WHITE  + "Download: " +
                TextFormatting.AQUA   + downloadUrl
            ));
        }
    }

    // --- tick listener -----------------------------------------------------

    public static final class TickListener {
        private boolean shown = false;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (shown || event.phase != TickEvent.Phase.END || !checkDone.get()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null || mc.player == null) return;
            shown = true;
            showNotification(mc);
            MinecraftForge.EVENT_BUS.unregister(this); // clean up
        }
    }

    // --- helpers -----------------------------------------------------------

    private static String fetchString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "SteamBridge/" + BuildInfo.VERSION);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                SteamBridgeMod.LOG.warn("[UpdateChecker] HTTP {} from {}", code, urlStr);
                return null;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Minimal JSON string-field extractor — avoids adding a JSON library dependency. */
    private static String extractField(String json, String key) {
        String needle = "\"" + key + "\"";
        int ki = json.indexOf(needle);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String computeLocalSha256() {
        try {
            URL loc = UpdateChecker.class.getProtectionDomain().getCodeSource().getLocation();
            File f  = new File(loc.toURI());
            if (!f.isFile()) return null; // running from IDE/exploded dir — skip

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[UpdateChecker] SHA-256 compute failed: {}", e.getMessage());
            return null;
        }
    }
}
