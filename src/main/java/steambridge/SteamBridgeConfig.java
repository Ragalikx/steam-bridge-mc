/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client config (JSON under config/steambridge.json). */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    // App ID is fixed to 480 (Spacewar) in SteamAppIdHelper; not configurable.
    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /** Voice UDP intercept (JVM DatagramSocket factory). Launch-only. */
    public static boolean interceptUdp    = true;
    /**
     * Remember the last game mode / allow-commands choice on the Open for Steam screen, per
     * world. Pokes at vanilla ShareToLanScreen internals through reflection, so if another
     * mod does something similar on the same screen, turn this off.
     */
    public static boolean disableOnlineModeOnPublish = true;
    public static String  sameNameAsHost = "allow";
    public static int     timeoutInitialSec = 30;
    public static int     timeoutConnectedSec = 60;
    public static String  stunServers = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";

    public static boolean kickOnSameNameAsHost() {
        return "kick".equalsIgnoreCase(sameNameAsHost);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "steambridge.json";

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(path)) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            if (o == null) return;
            if (o.has("allowWithoutAuth")) allowWithoutAuth = o.get("allowWithoutAuth").getAsBoolean();
            if (o.has("virtualPort"))      virtualPort      = o.get("virtualPort").getAsInt();
            if (o.has("interceptUdp"))     interceptUdp     = o.get("interceptUdp").getAsBoolean();
            if (o.has("disableOnlineModeOnPublish")) disableOnlineModeOnPublish = o.get("disableOnlineModeOnPublish").getAsBoolean();
            if (o.has("sameNameAsHost")) {
                sameNameAsHost = o.get("sameNameAsHost").getAsString();
                if (sameNameAsHost == null) sameNameAsHost = "allow";
                sameNameAsHost = sameNameAsHost.trim().toLowerCase();
                if (!sameNameAsHost.equals("allow") && !sameNameAsHost.equals("kick")) sameNameAsHost = "allow";
            }
            if (o.has("timeoutInitialSec")) timeoutInitialSec = o.get("timeoutInitialSec").getAsInt();
            else if (o.has("timeoutInitialMs")) timeoutInitialSec = Math.max(1, o.get("timeoutInitialMs").getAsInt() / 1000);
            if (o.has("timeoutConnectedSec")) timeoutConnectedSec = o.get("timeoutConnectedSec").getAsInt();
            else if (o.has("timeoutConnectedMs")) timeoutConnectedSec = Math.max(1, o.get("timeoutConnectedMs").getAsInt() / 1000);
            if (o.has("stunServers")) stunServers = o.get("stunServers").getAsString();

        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[SteamBridge] Failed to load config: {}", e.getMessage());
        }
    }

    public static void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        JsonObject o = new JsonObject();
        o.addProperty("allowWithoutAuth", allowWithoutAuth);
        o.addProperty("virtualPort", virtualPort);
        o.addProperty("interceptUdp", interceptUdp);
        o.addProperty("disableOnlineModeOnPublish", disableOnlineModeOnPublish);
        o.addProperty("sameNameAsHost", sameNameAsHost);
        o.addProperty("timeoutInitialSec", timeoutInitialSec);
        o.addProperty("timeoutConnectedSec", timeoutConnectedSec);
        o.addProperty("stunServers", stunServers);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(o, w);
            }
        } catch (IOException e) {
            SteamBridgeMod.LOG.warn("[SteamBridge] Failed to save config: {}", e.getMessage());
        }
    }
}
