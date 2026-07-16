/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import steambridge.proxy.CommonProxy;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Steam Bridge for Forge 1.7.10.
 * <p>
 * Client-focused (host/join over Steam P2P). No {@code clientSideOnly} on this Forge line,
 * so dedicated-server loads CommonProxy only and never touches Minecraft client classes.
 */
@Mod(
    modid = SteamBridgeMod.MODID,
    name = SteamBridgeMod.NAME,
    version = BuildInfo.VERSION,
    acceptedMinecraftVersions = "[1.7.10]"
)
public class SteamBridgeMod {

    public static final String MODID = "steambridge";
    public static final String NAME = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger(MODID);

    /**
     * Neutralises log4j message-lookup syntax in untrusted strings before logging
     * (1.7.10 ships a log4j line vulnerable to Log4Shell-style lookups).
     */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }

    @Mod.Instance(MODID)
    public static SteamBridgeMod instance;

    @SidedProxy(
        clientSide = "steambridge.proxy.ClientProxy",
        serverSide = "steambridge.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("=== Steam Bridge pre-init (Forge {}) v{} ===", BuildInfo.MC_VERSION, VERSION);
        SteamBridgeConfig.init(event.getSuggestedConfigurationFile());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("=== SteamBridge init (v{}) ===", VERSION);
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOG.info("=== SteamBridge postInit (v{}) ===", VERSION);
        proxy.postInit(event);
    }
}
