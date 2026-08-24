/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import net.minecraft.client.server.IntegratedServer;

/**
 * Auth policy for a world published over Steam.
 *
 * Vanilla Open-to-LAN does not flip online-mode off on modern versions.
 * A licensed host then sends an encryption HELLO. Cracked friends can still
 * get in via LAN fallbacks, but Mojang is contacted and a guest whose nick
 * matches the host can take the host profile. Turn auth off after publish.
 */
public final class HostAuth {

    private HostAuth() {}

    public static void applyAfterPublish(IntegratedServer server) {
        if (server == null || !SteamBridgeConfig.disableOnlineModeOnPublish) {
            return;
        }
        try {
            server.setUsesAuthentication(false);
            SteamBridgeMod.LOG.info("[SteamHost] Online-mode disabled for Steam LAN.");
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamHost] Could not disable online-mode: {}", t.toString());
        }
    }
}
