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

/** Steam Bridge entry (Fabric 1.16.5, client-only). */
public class SteamBridgeMod implements ClientModInitializer {

    public static final String MODID   = "steambridge";
    public static final String NAME    = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger(NAME);

    static {
        if (System.getProperty("jna.nosys") == null) {
            System.setProperty("jna.nosys", "true");
        }
    }

    @Override
    public void onInitializeClient() {
        LOG.info("=== Steam Bridge pre-init (Fabric 1.16.5) v{} ===", VERSION);

        SteamBridgeConfig.load();
        SteamClientEvents.register();
        VanillaGuiIntegration.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LOG.info("=== SteamBridge client setup: initializing Steam... ===");
            if (SteamBridgeConfig.interceptUdp) {
                steambridge.proxy.UdpInterceptFactory.install();
            }
            SteamAppIdHelper.ensureAppId(client.runDirectory);
            boolean ok = SteamManager.getInstance().init();
            LOG.info("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
        });
    }

    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }
}
