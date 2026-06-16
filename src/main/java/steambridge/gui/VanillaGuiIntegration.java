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
package steambridge.gui;

import steambridge.SteamBridgeMod;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.init.SoundEvents;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Side.CLIENT, modid = SteamBridgeMod.MODID)
public class VanillaGuiIntegration {

    private static final int BTN_FRIENDS    = 9002;
    private static final int BTN_STEAM_HOST = 9003;

    /** Pending SteamID to inject when the parent screen re-initializes. */
    static String pendingSteamId = null;
    /**
     * Pending server name to restore in GuiScreenAddServer after picking a friend.
     * Set alongside {@link #pendingSteamId} so the name field isn't lost.
     */
    static String pendingServerName = null;

    // -- Reflected fields ------------------------------------------------------

    private static Field fSavedServerList;
    private static Field fAddServerData;
    private static Field fAddServerIpField;
    private static Field fAddServerNameField;   // GuiScreenAddServer.serverNameField
    private static Field fShareToLanGameMode;
    private static Field fShareToLanAllowCommands;
    /** GuiConnecting.previousGuiScreen -> used to restore parent when the connect event is intercepted. */
    private static Field fConnectingPreviousScreen;

    static {
        try {
            fSavedServerList = ReflectionHelper.findField(GuiMultiplayer.class, "savedServerList", "field_146799_f");
            fSavedServerList.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fSavedServerList not found", e); }
        try {
            fAddServerData = ReflectionHelper.findField(GuiScreenAddServer.class, "serverData", "field_146311_h");
            fAddServerData.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fAddServerData not found", e); }
        try {
            fAddServerIpField = ReflectionHelper.findField(GuiScreenAddServer.class, "serverIPField", "field_146308_f");
            fAddServerIpField.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fAddServerIpField not found", e); }
        try {
            fAddServerNameField = ReflectionHelper.findField(GuiScreenAddServer.class, "serverNameField", "field_146309_g");
            fAddServerNameField.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fAddServerNameField not found", e); }
        try {
            fShareToLanGameMode = ReflectionHelper.findField(GuiShareToLan.class, "gameMode", "field_146599_h");
            fShareToLanGameMode.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fShareToLanGameMode not found", e); }
        try {
            fShareToLanAllowCommands = ReflectionHelper.findField(GuiShareToLan.class, "allowCommands", "field_146600_i");
            fShareToLanAllowCommands.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fShareToLanAllowCommands not found", e); }
        try {
            fConnectingPreviousScreen = ReflectionHelper.findField(GuiConnecting.class, "previousGuiScreen", "field_146545_a");
            fConnectingPreviousScreen.setAccessible(true);
        } catch (Exception e) { SteamBridgeMod.LOG.warn("fConnectingPreviousScreen not found", e); }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Returns {@code true} if {@code ip} looks like a Steam64 ID.
     * Strips any trailing {@code :port} before checking.
     * Steam64 IDs are 17-digit numbers starting with {@code 7656119}.
     */
    private static boolean isSteamServerId(String ip) {
        if (ip == null) return false;
        String host = ip.trim();
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.length() >= 15 && host.startsWith("7656119") && host.matches("\\d+");
    }

    /** Extracts the pure numeric SteamID (no port) from a server IP string. */
    private static String extractSteamId(String ip) {
        String host = ip.trim();
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    /** Finds the IP text field in a screen (by reflection or heuristic scan). */
    private static GuiTextField findIpTextField(GuiScreen gui) {
        if (gui instanceof GuiScreenAddServer && fAddServerIpField != null) {
            try { return (GuiTextField) fAddServerIpField.get(gui); } catch (Exception ignored) {}
        }
        List<GuiTextField> fields = new ArrayList<>();
        for (Field f : gui.getClass().getDeclaredFields()) {
            if (GuiTextField.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try { GuiTextField tf = (GuiTextField) f.get(gui); if (tf != null) fields.add(tf); }
                catch (Exception ignored) {}
            }
        }
        if (fields.size() >= 2) return fields.get(1);
        if (fields.size() == 1) return fields.get(0);
        return null;
    }

    // -- Steam-server marking --------------------------------------------------

    /**
     * Prevents MC from sending a TCP ping to a Steam server entry.
     */
    private static void markSteamServer(ServerData data) {
        data.pinged = true;
    }

    /** Iterates the saved server list of {@code gui} and marks all Steam entries. */
    private static void markAllSteamServers(GuiScreen gui) {
        if (fSavedServerList == null) return;
        try {
            ServerList list = (ServerList) fSavedServerList.get(gui);
            if (list == null) return;
            int steamIndex = 0;
            for (int i = 0; i < list.countServers(); i++) {
                ServerData data = list.getServerData(i);
                if (isSteamServerId(data.serverIP)) {
                    markSteamServer(data);
                    if (i > steamIndex) list.swapServers(i, steamIndex);
                    steamIndex++;
                }
            }
        } catch (Exception ignored) {}
    }

    // -- Event handlers --------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiMultiplayer) {
            markAllSteamServers(mc.currentScreen);
        }
    }

    @SubscribeEvent
    public static void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        // Final guard right before MC draws each frame -> ensures no ping thread starts
        if (event.getGui() instanceof GuiMultiplayer) {
            markAllSteamServers(event.getGui());
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        GuiScreen next = event.getGui();
        Minecraft mc   = Minecraft.getMinecraft();

        if (next instanceof GuiShareToLan) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server != null && server.isRunning()) {
                event.setCanceled(true);
                mc.displayGuiScreen(new GuiSteamHostManagement(mc.currentScreen));
                return;
            }
            if (mc.getIntegratedServer() != null) {
                try {
                    String worldKey = mc.getIntegratedServer().getFolderName();
                    steambridge.steam.SteamSocial.Worlds.Settings saved =
                            steambridge.steam.SteamSocial.Worlds.get().load(worldKey);
                    if (fShareToLanGameMode != null)      fShareToLanGameMode.set(next, saved.gametype.toLowerCase(java.util.Locale.ROOT));
                    if (fShareToLanAllowCommands != null)  fShareToLanAllowCommands.set(next, saved.allowCommands);
                } catch (Exception e) { SteamBridgeMod.LOG.warn("Failed to load GuiShareToLan defaults", e); }
            }
        }

        if (next instanceof GuiConnecting) {
            ServerData serverData = mc.getCurrentServerData();
            if (serverData != null && isSteamServerId(serverData.serverIP)) {
                long steamId = Long.parseLong(extractSteamId(serverData.serverIP));
                SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
                SteamClient active = SteamManager.getInstance().getActiveClient();
                if (active != null) active.disconnect();
                SteamClient client = new SteamClient();
                client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), mc.currentScreen);
                event.setCanceled(true);
                // Resolve the proper parent screen from GuiConnecting (vanilla sets it before interception)
                GuiScreen connectingParent = mc.currentScreen;
                if (fConnectingPreviousScreen != null) {
                    try {
                        GuiScreen p = (GuiScreen) fConnectingPreviousScreen.get(next);
                        if (p != null) connectingParent = p;
                    } catch (Exception ignored) {}
                }
                mc.displayGuiScreen(new GuiSteamConnecting(connectingParent, client));
            }
        }

    }

    @SubscribeEvent
    public static void onInitGuiPost(GuiScreenEvent.InitGuiEvent.Post event) {
        GuiScreen gui = event.getGui();

        // -- Apply pending SteamID (and restored server name) ------------------
        if (pendingSteamId != null) {
            final String sid  = pendingSteamId;
            final String name = pendingServerName;
            pendingSteamId   = null;
            pendingServerName = null;

            if (gui instanceof GuiScreenAddServer) {
                // Set IP in both the data object and the visible text field
                try {
                    if (fAddServerData != null) {
                        ServerData sd = (ServerData) fAddServerData.get(gui);
                        if (sd != null) sd.serverIP = sid;
                    }
                    if (fAddServerIpField != null) {
                        GuiTextField ipTf = (GuiTextField) fAddServerIpField.get(gui);
                        if (ipTf != null) ipTf.setText(sid);
                    }
                    // Restore the server name the user had typed before picking a friend
                    if (name != null && fAddServerNameField != null) {
                        GuiTextField nameTf = (GuiTextField) fAddServerNameField.get(gui);
                        if (nameTf != null) nameTf.setText(name);
                    }
                } catch (Exception ignored) {}
                // Enable "Add" button (id=0) if name is non-empty
                if (name != null && !name.isEmpty()) {
                    for (GuiButton b : event.getButtonList()) {
                        if (b.id == 0) { b.enabled = true; break; }
                    }
                }
            } else {
                // GuiScreenDirectConnect or similar -> just fill the IP field
                GuiTextField tf = findIpTextField(gui);
                if (tf != null) tf.setText(sid);
                // Enable the "Connect" button (id=1 in GuiScreenDirectConnect)
                for (GuiButton b : event.getButtonList()) {
                    if (b.id == 1) { b.enabled = true; break; }
                }
            }
        }

        // -- Share-to-LAN button rearrangement ---------------------------------
        if (gui instanceof GuiShareToLan) {
            GuiButton startLan = null, cancel = null;
            for (GuiButton b : event.getButtonList()) {
                if (b.id == 101) startLan = b;
                if (b.id == 102) cancel   = b;
            }
            if (startLan != null && cancel != null) {
                startLan.width = 98;
                startLan.x     = gui.width / 2 - 155;
                event.getButtonList().add(new GuiButton(BTN_STEAM_HOST, gui.width / 2 - 49, gui.height - 28, 98, 20,
                        net.minecraft.client.resources.I18n.format("steambridge.gui.open_steam")));
                cancel.width = 98;
                cancel.x     = gui.width / 2 + 57;
            }
        }

        // -- Friends "S" button next to the server-address field ---------------
        if (gui instanceof GuiScreenAddServer || gui instanceof GuiScreenServerList) {
            GuiTextField ipField = findIpTextField(gui);
            if (ipField != null) {
                event.getButtonList().add(new GuiButton(BTN_FRIENDS,
                        ipField.x + ipField.width + 4, ipField.y, 20, 20, "S"));
            }
        }

        // -- Pre-mark Steam servers + schedule a second pass before first render -
        if (gui instanceof GuiMultiplayer) {
            markAllSteamServers(gui);
            // addScheduledTask runs before the very next render loop -> belt-and-suspenders
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().currentScreen == gui) markAllSteamServers(gui);
            });
        }

        // -- In-game menu: update "Open to LAN" button for Steam host ----------
        if (gui instanceof GuiIngameMenu) {
            Minecraft mc = Minecraft.getMinecraft();
            SteamServer server = SteamManager.getInstance().getActiveServer();
            boolean isSteam = server != null && server.isRunning();
            boolean isLan   = mc.getIntegratedServer() != null && mc.getIntegratedServer().getPublic();
            for (GuiButton btn : event.getButtonList()) {
                if (btn.id == 7) {
                    if (isSteam) {
                        btn.enabled       = true;
                        btn.displayString = net.minecraft.client.resources.I18n.format("steambridge.gui.manage_session");
                    } else if (isLan) {
                        btn.displayString += " (LAN)";
                    }
                    break;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onActionPerformedPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getGui() instanceof GuiIngameMenu && event.getButton().id == 7) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server != null && server.isRunning()) {
                event.setCanceled(true);
                Minecraft.getMinecraft().displayGuiScreen(new GuiSteamHostManagement(event.getGui()));
            }
        }
    }

    @SubscribeEvent
    public static void onActionPerformedPost(GuiScreenEvent.ActionPerformedEvent.Post event) {
        GuiScreen gui = event.getGui();
        GuiButton btn = event.getButton();

        // -- Open for Steam (in Share-to-LAN screen) ---------------------------
        if (gui instanceof GuiShareToLan && btn.id == BTN_STEAM_HOST) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getIntegratedServer() != null) {
                boolean ac = false;
                String gm  = "survival";
                try {
                    if (fShareToLanGameMode != null)     gm = (String) fShareToLanGameMode.get(gui);
                    if (fShareToLanAllowCommands != null) ac = (Boolean) fShareToLanAllowCommands.get(gui);
                } catch (Exception ignored) {}

                net.minecraft.world.GameType gameType =
                        net.minecraft.world.GameType.parseGameTypeWithDefault(gm, net.minecraft.world.GameType.SURVIVAL);
                String worldKey = mc.getIntegratedServer().getFolderName();
                steambridge.steam.SteamSocial.Worlds.get().save(
                        worldKey, gameType, ac,
                        steambridge.steam.SteamServer.AccessPolicy.EVERYONE,
                        steambridge.steam.SteamServer.TransportMode.AUTO);

                String port = mc.getIntegratedServer().shareToLAN(gameType, ac);
                SteamServer server = new SteamServer(SteamServer.AccessPolicy.EVERYONE, worldKey, "World");
                if (port != null) { try { server.setMcPort(Integer.parseInt(port)); } catch (NumberFormatException ignored) {} }
                server.start();

                mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
                mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString("\u00A7bSteam Host started!"));
                mc.displayGuiScreen(null);
                mc.setIngameFocus();
            }
        }

        // -- Friends button ----------------------------------------------------
        if (btn.id == BTN_FRIENDS) {
            Minecraft mc = Minecraft.getMinecraft();

            // Save server name field content so it survives the screen reinit
            if (gui instanceof GuiScreenAddServer && fAddServerNameField != null) {
                try {
                    GuiTextField nameTf = (GuiTextField) fAddServerNameField.get(gui);
                    pendingServerName = nameTf != null ? nameTf.getText() : null;
                } catch (Exception ignored) { pendingServerName = null; }
            } else {
                pendingServerName = null;
            }

            if (SteamManager.getInstance().isInitialized()) {
                mc.displayGuiScreen(new GuiSteamFriends(gui, null, steamId -> pendingSteamId = steamId));
            } else {
                mc.displayGuiScreen(new GuiSteamResync(gui, steamId -> pendingSteamId = steamId));
            }
        }
    }
}

