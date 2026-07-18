/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import steambridge.platform.Services;

/** Client config (JSON under config/steambridge.json). */
public final class SteamBridgeConfig {

    private SteamBridgeConfig() {}

    // App ID is fixed to 480 (Spacewar) in SteamAppIdHelper; not configurable.
    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    /** Voice UDP intercept (JVM DatagramSocket factory). Launch-only. */
    public static boolean interceptUdp    = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "steambridge.json";

    public static void load() {
        Path path = Services.PLATFORM.getConfigDirectory().resolve(FILE_NAME);
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
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[SteamBridge] Failed to load config: {}", e.getMessage());
        }
    }

    public static void save() {
        Path path = Services.PLATFORM.getConfigDirectory().resolve(FILE_NAME);
        JsonObject o = new JsonObject();
        o.addProperty("allowWithoutAuth", allowWithoutAuth);
        o.addProperty("virtualPort", virtualPort);
        o.addProperty("interceptUdp", interceptUdp);
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
