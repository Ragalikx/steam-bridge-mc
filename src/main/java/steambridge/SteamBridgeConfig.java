/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Simple forge {@link Configuration} (1.8.9 has no {@code @Config} annotation API).
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
            allowWithoutAuth = config.getBoolean(
                "Allow Without Auth",
                Configuration.CATEGORY_GENERAL,
                true,
                "Allow connections without validating Steam Auth Ticket."
            );
            virtualPort = config.getInt(
                "Virtual Port",
                Configuration.CATEGORY_GENERAL,
                0, 0, 65535,
                "Virtual port for Steam network. 0 is default."
            );
            rememberNetworkSettings = config.getBoolean(
                "Remember Network Settings",
                Configuration.CATEGORY_GENERAL,
                true,
                "Remember the last game mode / allow-commands choice on the Open for Steam screen, per world. Uses reflection into vanilla's own screen fields, so if another mod also messes with that screen and something looks off, turn this off."
            );
            if (config.hasChanged()) {
                config.save();
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("Failed to load steambridge config: {}", e.toString());
        }
    }
}
