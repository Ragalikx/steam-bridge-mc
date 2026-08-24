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
    /** After Open-for-Steam, force the integrated server off Mojang auth. */
    public static boolean disableOnlineModeOnPublish = true;
    /**
     * What to do when a Steam guest uses the host's Minecraft name.
     * {@code allow} = let them in as an offline-UUID player (1.7.10 does this
     * naturally once online-mode is off). {@code kick} = disconnect with a message.
     */
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

            disableOnlineModeOnPublish = config.get(
                Configuration.CATEGORY_GENERAL,
                "Disable Online Mode On Publish",
                true,
                "After opening the world for Steam, turn off Mojang online-mode. Licensed hosts otherwise send an encryption HELLO and a guest with the same nick as the host can get stuck. Leave on unless you know you need Mojang auth on LAN."
            ).getBoolean(true);

            sameNameAsHost = config.get(
                Configuration.CATEGORY_GENERAL,
                "Same Name As Host",
                "allow",
                "When a Steam guest uses the host Minecraft name: allow (offline UUID, they can play) or kick (disconnect with a message)."
            ).getString();
            if (sameNameAsHost == null) sameNameAsHost = "allow";
            sameNameAsHost = sameNameAsHost.trim().toLowerCase();
            if (!sameNameAsHost.equals("allow") && !sameNameAsHost.equals("kick")) {
                sameNameAsHost = "allow";
            }
            timeoutInitialSec = clamp(
                config.get(Configuration.CATEGORY_GENERAL, "Timeout Initial Seconds", 30,
                    "Steam P2P initial route timeout in seconds.").getInt(30),
                5, 120);
            timeoutConnectedSec = clamp(
                config.get(Configuration.CATEGORY_GENERAL, "Timeout Connected Seconds", 60,
                    "Steam drop timeout after the connection is up, in seconds. Raise if heavy loading screens stall the client thread.").getInt(60),
                10, 300);

            stunServers = config.get(
                Configuration.CATEGORY_GENERAL,
                "Stun Servers",
                "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302",
                "Comma-separated STUN servers for ICE / direct P2P. Empty = skip override."
            ).getString();
            if (stunServers == null) stunServers = "";

            if (config.hasChanged()) {
                config.save();
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("Failed to load steambridge config: {}", e.toString());
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
