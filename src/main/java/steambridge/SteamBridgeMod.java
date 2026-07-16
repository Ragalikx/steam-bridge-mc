/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.event.SteamClientEvents;
import steambridge.steam.SteamManager;

/**
 * Steam Bridge entry point (Forge 1.16.5).
 *
 * <p>Client-only mod: launches the Steam bridge during client setup and wires up the
 * client-side event handlers. Ported from the Forge 1.19.2 / NeoForge lifecycle.</p>
 */
@Mod(SteamBridgeMod.MODID)
public class SteamBridgeMod {

    public static final String MODID = "steambridge";
    public static final String NAME  = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger(MODID);

    static {
        // Forge 1.16.5 puts JNA 4.4.0 on the game library path. If a newer/mismatched
        // jnidispatch was left in %TEMP% (or on PATH) from another MC version, Native
        // clinit fails with "incompatible JNA native library" and Steam never starts.
        // nosys forces JNA to use only the native bundled with the classpath jar.
        if (System.getProperty("jna.nosys") == null) {
            System.setProperty("jna.nosys", "true");
        }
    }

    public SteamBridgeMod() {
        LOG.info("=== Steam Bridge pre-init (Forge 1.16.5) v{} ===", VERSION);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        modBus.addListener(SteamBridgeConfig::onLoad);
        modBus.addListener(SteamBridgeConfig::onReload);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SteamBridgeConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(new SteamClientEvents());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LOG.info("=== SteamBridge client setup - initializing Steam... ===");
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
