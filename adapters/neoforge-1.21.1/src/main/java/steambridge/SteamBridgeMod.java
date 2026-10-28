/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.event.SteamClientEvents;
import steambridge.steam.SteamManager;

/** Steam Bridge entry (NeoForge 1.21.1, client-only). */
@Mod(SteamBridgeMod.MODID)
public class SteamBridgeMod {

    public static final String MODID = "steambridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger("steambridge");

    public SteamBridgeMod(IEventBus modEventBus, ModContainer modContainer) {
        SteamBridgeConfig.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
        LOG.debug("=== Steam Bridge pre-init (NeoForge 1.21.1) v{} ===", VERSION);

        modEventBus.addListener(this::onClientSetup);

        NeoForge.EVENT_BUS.register(new SteamClientEvents());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(
                () -> {
                    LOG.debug("=== SteamBridge client setup: initializing Steam... ===");
                    if (steambridge.voice.VoiceIntegration.needsLegacyFactory()) {
                        steambridge.proxy.UdpInterceptFactory.install();
                    }
                    SteamAppIdHelper.ensureAppId(Minecraft.getInstance().gameDirectory);
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
