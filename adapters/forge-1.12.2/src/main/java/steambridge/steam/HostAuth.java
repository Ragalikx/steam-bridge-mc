/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import net.minecraft.server.MinecraftServer;

import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

public final class HostAuth {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private HostAuth() {}

    public static void applyAfterPublish(MinecraftServer server) {
        if (server == null || !SteamBridgeConfig.settings().disableOnlineModeOnPublish) {
            return;
        }
        try {
            server.setOnlineMode(false);
            SteamBridgeMod.LOG.debug("[SteamHost] Online-mode disabled for Steam LAN.");
        } catch (Throwable t) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "HostAuth-warn-1",
                    "[SteamHost] Could not disable online-mode: {}",
                    t.toString());
        }
    }
}
