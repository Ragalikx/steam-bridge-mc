/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.event.SteamClientEvents;
import steambridge.gui.VanillaGuiIntegration;
import steambridge.steam.SteamManager;

/** Steam Bridge entry (Fabric 1.20.1, client-only). */
public class SteamBridgeMod implements ClientModInitializer {

    public static final String NAME = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger("steambridge");

    @Override
    public void onInitializeClient() {
        LOG.debug("=== Steam Bridge pre-init (Fabric 1.20.1) v{} ===", VERSION);

        SteamBridgeConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        SteamClientEvents.register();
        VanillaGuiIntegration.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> {
                    LOG.debug("=== SteamBridge client setup: initializing Steam... ===");
                    if (steambridge.voice.VoiceIntegration.needsLegacyFactory()) {
                        steambridge.proxy.UdpInterceptFactory.install();
                    }
                    SteamAppIdHelper.ensureAppId(client.gameDirectory);
                    boolean ok = SteamManager.getInstance().init();
                    LOG.debug("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
                });
    }

    /** Strip log4j lookup syntax from untrusted strings before logging. */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }
}
