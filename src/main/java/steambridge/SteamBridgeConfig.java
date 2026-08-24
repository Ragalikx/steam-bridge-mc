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
    public static boolean disableOnlineModeOnPublish = true;
    public static String sameNameAsHost = "allow";
    public static int timeoutInitialSec = 30;
    public static int timeoutConnectedSec = 60;
    public static String stunServers = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";

    public static boolean kickOnSameNameAsHost() {
        return "kick".equalsIgnoreCase(sameNameAsHost);
    }

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

            disableOnlineModeOnPublish = config.getBoolean(
                "Disable Online Mode On Publish",
                Configuration.CATEGORY_GENERAL,
                true,
                "After opening the world for Steam, turn off Mojang online-mode."
            );
            sameNameAsHost = config.getString(
                "Same Name As Host",
                Configuration.CATEGORY_GENERAL,
                "allow",
                "When a Steam guest uses the host Minecraft name: allow or kick."
            );
            timeoutInitialSec = config.getInt("Timeout Initial Seconds", Configuration.CATEGORY_GENERAL, 30, 5, 120, "Steam P2P initial route timeout in seconds.");
            timeoutConnectedSec = config.getInt("Timeout Connected Seconds", Configuration.CATEGORY_GENERAL, 60, 10, 300, "Steam drop timeout after the connection is up, in seconds.");
            stunServers = config.getString("Stun Servers", Configuration.CATEGORY_GENERAL, "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302", "STUN servers for ICE.");

            if (config.hasChanged()) {
                config.save();
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("Failed to load steambridge config: {}", e.toString());
        }
    }
}
