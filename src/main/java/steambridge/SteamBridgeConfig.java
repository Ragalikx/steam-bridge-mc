/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;

/**
 * Forge {@link Configuration} for 1.7.10 (classic Property API).
 * Voice / UDP intercept is intentionally absent on this branch.
 */
public final class SteamBridgeConfig {

    public static boolean allowWithoutAuth = true;
    public static int virtualPort = 0;
    public static boolean rememberNetworkSettings = true;

    private static Configuration config;

    private SteamBridgeConfig() {}

    public static void init(File configFile) {
        config = new Configuration(configFile);
        sync();
    }

    public static void sync() {
        if (config == null) return;
        try {
            config.load();
            Property auth = config.get(
                Configuration.CATEGORY_GENERAL,
                "Allow Without Auth",
                true,
                "Allow connections without validating Steam Auth Ticket."
            );
            allowWithoutAuth = auth.getBoolean(true);

            Property port = config.get(
                Configuration.CATEGORY_GENERAL,
                "Virtual Port",
                0,
                "Virtual port for Steam network. 0 is default."
            );
            virtualPort = Math.max(0, Math.min(65535, port.getInt(0)));

            Property remember = config.get(
                Configuration.CATEGORY_GENERAL,
                "Remember Network Settings",
                true,
                "Remember the last game mode / allow-commands choice on the Open for Steam screen, per world. Uses reflection into vanilla's own screen fields, so if another mod also messes with that screen and something looks off, turn this off."
            );
            rememberNetworkSettings = remember.getBoolean(true);

            if (config.hasChanged()) {
                config.save();
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("Failed to load steambridge config: {}", e.toString());
        }
    }
}
