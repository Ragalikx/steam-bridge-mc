/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import steambridge.event.SteamClientEvents;
import steambridge.steam.SteamManager;

/**
 * Steam Bridge entry point (NeoForge 1.21.1).
 *
 * <p>Client-only mod: launches the Steam bridge during client setup and wires up the
 * client-side event handlers. In NeoForge 1.21.1 the {@code @Mod} constructor receives
 * {@link IEventBus} and {@link ModContainer} via injection, so static
 * {@code FMLJavaModLoadingContext.get()} calls are not needed.</p>
 */
@Mod(SteamBridgeMod.MODID)
public class SteamBridgeMod {

    public static final String MODID   = "steambridge";
    public static final String NAME    = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogUtils.getLogger();

    public SteamBridgeMod(IEventBus modEventBus, ModContainer modContainer) {
        LOG.info("=== Steam Bridge pre-init (NeoForge 1.21.1) v{} ===", VERSION);

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(SteamBridgeConfig::onLoad);
        modEventBus.addListener(SteamBridgeConfig::onReload);

        modContainer.registerConfig(ModConfig.Type.CLIENT, SteamBridgeConfig.SPEC);

        // NeoForge game event bus: client-side gameplay/GUI hooks.
        NeoForge.EVENT_BUS.register(new SteamClientEvents());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LOG.info("=== SteamBridge client setup: initializing Steam... ===");
            // Install UDP intercept factory before any voice mod creates DatagramSockets.
            if (SteamBridgeConfig.interceptUdp) {
                steambridge.proxy.UdpInterceptFactory.install();
            }
            SteamAppIdHelper.ensureAppId(Minecraft.getInstance().gameDirectory);
            boolean ok = SteamManager.getInstance().init();
            LOG.info("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
        });
    }

    /**
     * Neutralises log4j message-lookup syntax in untrusted strings (Steam persona names,
     * Minecraft names, remote disconnect messages) before they reach the logger.
     */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }
}
