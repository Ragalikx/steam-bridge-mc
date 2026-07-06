/*
 * Copyright (c) 2019-2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = SteamBridgeMod.MODID, name = "steambridge")
public class SteamBridgeConfig {

    @Config.Comment("Allow connections without validating Steam Auth Ticket. False is more secure but might affect some NAT types.")
    @Config.Name("Allow Without Auth")
    public static boolean allowWithoutAuth = true;

    @Config.Comment("Virtual port for Steam network. 0 is default. Change only if conflicting with other mods.")
    @Config.Name("Virtual Port")
    public static int virtualPort = 0;


    // NOTE: the Steam App ID is intentionally NOT configurable. It is hardcoded to 480
    // (Spacewar) in SteamAppIdHelper. Letting users point it at a real game's App ID -
    // especially one with an anti-cheat (VAC/EAC) - would get their account banned and
    // damage the mod's reputation, so the option is removed entirely.

    @Mod.EventBusSubscriber(modid = SteamBridgeMod.MODID)
    private static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(SteamBridgeMod.MODID)) {
                ConfigManager.sync(SteamBridgeMod.MODID, Config.Type.INSTANCE);
            }
        }
    }
}

