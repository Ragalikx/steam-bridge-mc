/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import net.minecraft.server.MinecraftServer;

public final class HostAuth {

    private HostAuth() {}

    public static void applyAfterPublish(MinecraftServer server) {
        if (server == null || !SteamBridgeConfig.disableOnlineModeOnPublish) {
            return;
        }
        try {
            server.setOnlineMode(false);
            SteamBridgeMod.LOG.info("[SteamHost] Online-mode disabled for Steam LAN.");
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamHost] Could not disable online-mode: {}", t.toString());
        }
    }
}
