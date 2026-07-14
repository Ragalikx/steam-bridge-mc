/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import steambridge.event.SteamClientEvents;
import steambridge.steam.SteamManager;

/**
 * Steam Bridge entry point (NeoForge 1.20.1).
 *
 * <p>Client-only mod: launches the Steam bridge during client setup and wires up the
 * client-side event handlers. Ported from the 1.12.2 {@code @Mod}/{@code @SidedProxy}
 * lifecycle.</p>
 */
@Mod(SteamBridgeMod.MODID)
public class SteamBridgeMod {

    public static final String MODID = "steambridge";
    public static final String NAME  = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogUtils.getLogger();

    public SteamBridgeMod() {
        LOG.info("=== Steam Bridge pre-init (NeoForge 1.20.1) v{} ===", VERSION);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        modBus.addListener(SteamBridgeConfig::onLoad);
        modBus.addListener(SteamBridgeConfig::onReload);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SteamBridgeConfig.SPEC);

        // Forge event bus: client-side gameplay/GUI hooks (ported from ClientProxy).
        MinecraftForge.EVENT_BUS.register(new SteamClientEvents());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LOG.info("=== SteamBridge client setup - initializing Steam... ===");
            // Install UDP intercept factory before any voice mod creates DatagramSockets.
            if (SteamBridgeConfig.interceptUdp) {
                steambridge.proxy.UdpInterceptFactory.install();
            }
            // Ensure steam_appid.txt exists in the game dir before SteamAPI.init()
            SteamAppIdHelper.ensureAppId(Minecraft.getInstance().gameDirectory);
            boolean ok = SteamManager.getInstance().init();
            LOG.info("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
        });
    }

    /**
     * Neutralises log4j message-lookup syntax in untrusted strings (Steam persona names,
     * Minecraft names, remote disconnect messages) before they reach the logger.
     * Retained from the 1.12.2 build as a defensive measure for remote-controlled text.
     */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }
}
