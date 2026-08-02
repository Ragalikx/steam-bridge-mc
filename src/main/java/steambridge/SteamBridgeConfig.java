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

    public static boolean allowWithoutAuth = true;
    public static int     virtualPort      = 0;
    public static boolean interceptUdp    = true;
    /**
     * Remember the last game mode / allow-commands choice on the Open for Steam screen, per
     * world. Pokes at vanilla OpenToLanScreen internals through reflection, so if another
     * mod does something similar on the same screen, turn this off.
     */
    public static boolean rememberNetworkSettings = true;

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
            if (o.has("rememberNetworkSettings")) rememberNetworkSettings = o.get("rememberNetworkSettings").getAsBoolean();
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
        o.addProperty("rememberNetworkSettings", rememberNetworkSettings);
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
