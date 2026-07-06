/*
 * Copyright (c) 2019-2026 Ragalikx
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
    modid   = SteamBridgeMod.MODID,
    name    = SteamBridgeMod.NAME,
    version = BuildInfo.VERSION,
    clientSideOnly = true
)
public class SteamBridgeMod {

    public static final String MODID   = "steambridge";
    public static final String NAME    = "Steam Bridge";
    public static final String VERSION = BuildInfo.VERSION;

    public static final Logger LOG = LogManager.getLogger(MODID);

    /**
     * Neutralises log4j message-lookup syntax in untrusted strings (Steam persona names,
     * Minecraft names, remote disconnect messages) before they reach the logger.
     * <p>
     * Minecraft 1.12.2 ships with a log4j version vulnerable to "Log4Shell"
     * (CVE-2021-44228): a logged string containing {@code ${jndi:ldap://...}} can trigger
     * remote class loading and code execution. A remote peer fully controls their Steam
     * display name, so any such string MUST be defanged before logging. Inserts a
     * zero-width space after every {@code $} that precedes a {@code {}, which breaks the
     * lookup token while staying visually identical in the log.
     */
    public static String safeLog(String s) {
        if (s == null) {
            return "";
        }
        // Break the "${" lookup token with a zero-width space (U+200B).
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
        LOG.info("=== SteamBridge pre-init (v{}) ===", VERSION);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("=== SteamBridge init ===");
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOG.info("=== SteamBridge postInit - initializing Steam... ===");

        // Ensure steam_appid.txt exists in .minecraft before SteamAPI.init()
        SteamAppIdHelper.ensureAppId(Minecraft.getMinecraft().gameDir);

        boolean ok = SteamManager.getInstance().init();
        LOG.info("=== Steam init result: {} ===", ok ? "SUCCESS" : "FAILED");
    }
}
