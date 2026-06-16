/*
 * Copyright (c) 2019-2026 Ragalikx
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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

    @Config.Comment("Check for mod updates on startup. Fetches version.json from GitHub in a background thread; " +
            "shows a chat notification once when you join a world. Disable to skip all network requests.")
    @Config.Name("Check For Updates")
    public static boolean checkForUpdates = true;

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

