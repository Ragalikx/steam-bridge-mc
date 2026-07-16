/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import steambridge.proxy.ClientProxy;
import steambridge.proxy.CommonProxy;
import steambridge.steam.SteamManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = SteamBridgeMod.MODID,
    name = SteamBridgeMod.NAME,
    version = BuildInfo.VERSION,
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public class SteamBridgeMod {

    public static final String MODID = "steambridge";
    public static final String NAME = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger(MODID);

    /**
     * Neutralises log4j message-lookup syntax in untrusted strings before logging
     * (Minecraft 1.8.9 ships a log4j vulnerable to Log4Shell-style lookups).
     */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf("${") < 0 ? s : s.replace("${", "$" + ((char) 0x200B) + "{");
    }

    @Mod.Instance
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
        LOG.info("=== SteamBridge postInit (v{}) - initializing Steam... ===", VERSION);
        // No DatagramSocket intercept / voice tunnel on 1.8.9.
        SteamAppIdHelper.ensureAppId(Minecraft.getMinecraft().mcDataDir);
        boolean ok = SteamManager.getInstance().init();
        LOG.info("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
    }
}
