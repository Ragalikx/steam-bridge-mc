/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import net.minecraft.client.server.IntegratedServer;

import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

/**
 * Auth policy for a world published over Steam.
 *
 * <p>Vanilla Open-to-LAN does not flip online-mode off on modern versions. A licensed host then
 * sends an encryption HELLO. Cracked friends can still get in via LAN fallbacks, but Mojang is
 * contacted and a guest whose nick matches the host can take the host profile. Turn auth off after
 * publish.
 */
public final class HostAuth {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private HostAuth() {}

    public static void applyAfterPublish(IntegratedServer server) {
        if (server == null || !SteamBridgeConfig.settings().disableOnlineModeOnPublish) {
            return;
        }
        try {
            server.setUsesAuthentication(false);
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
