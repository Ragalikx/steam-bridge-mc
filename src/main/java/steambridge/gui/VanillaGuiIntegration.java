/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.SteamAppIdHelper;
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

    private static final int BTN_FRIENDS        = 9002;
    private static final int BTN_STEAM_HOST     = 9003;
    private static final int BTN_TRANSPORT_MODE = 9004;
    private static final int BTN_ACCESS_POLICY  = 9005;

    /**
     * Transport route the host will start with, cycled by the button on the Share-to-LAN screen.
     * Loaded from the world's saved settings when that screen opens; defaults to AUTO.
     */
    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    
    /**
     * Access policy (Friends/Everyone), toggled by the button on the Share-to-LAN screen.
     */
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;

    /** Pending SteamID to inject when the parent screen re-initializes. */
    static String pendingSteamId = null;
    /**
     * Pending server name to restore in GuiScreenAddServer after picking a friend.
     * Set alongside {@link #pendingSteamId} so the name field isn't lost.
     */
    static String pendingServerName = null;
    /**
     * When true the next Draw frame will force-enable the Connect button (id=1).
     * Needed because vanilla's updateScreen() resets {@code enabled} every tick
     * and does not react to programmatic {@code setText()} calls.
     */
    private static boolean pendingConnectEnable = false;

    private static Field fAddServerData;
    private static Field fAddServerIpField;
    private static Field fAddServerNameField;
    private static Field fShareToLanGameMode;
    private static Field fShareToLanAllowCommands;
    /** GuiConnecting.previousGuiScreen (SRG field_146374_i). */
    private static Field fConnectingPreviousScreen;

    static {
        fAddServerData = resolveField(GuiScreenAddServer.class, "serverData", "field_146311_h");
        fAddServerIpField = resolveField(GuiScreenAddServer.class, "serverIPField", "field_146308_f");
        fAddServerNameField = resolveField(GuiScreenAddServer.class, "serverNameField", "field_146309_g");
        fShareToLanGameMode = resolveField(GuiShareToLan.class, "gameMode", "field_146599_h");
        fShareToLanAllowCommands = resolveField(GuiShareToLan.class, "allowCommands", "field_146600_i");
        fConnectingPreviousScreen = resolveField(GuiConnecting.class, "previousGuiScreen", "field_146374_i");
    }

    private static Field resolveField(Class<?> owner, String... names) {
        try {
            Field f = ReflectionHelper.findField(owner, names);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("Reflection field not found on {}: {}", owner.getSimpleName(), names[0], e);
            return null;
        }
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Returns {@code true} if {@code ip} looks like a Steam64 ID.
     * Strips any trailing {@code :port} before checking.
     * Steam64 IDs are 17-digit numbers starting with {@code 7656119}.
     */
    
    private static void beginSteamConnect(GuiScreen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) active.disconnect();
        SteamClient client = new SteamClient();
        client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
        net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(new GuiSteamConnecting(parent, client));
    }
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
     * Skip TCP ping for a SteamID entry and set safe list display fields + avatar when ready.
     * Sets {@code pinged=true} so ServerPinger never does DNS on a Steam64 host.
     */
    private static void markSteamServer(ServerData data) {
        data.pinged = true;
        data.pingToServer = 0L;
        data.serverMOTD = net.minecraft.client.resources.I18n.format("steambridge.gui.server_steam_motd");
        data.populationInfo = net.minecraft.client.resources.I18n.format("steambridge.gui.server_steam_status");
        if (data.gameVersion == null || data.gameVersion.isEmpty()) {
            data.gameVersion = "Steam";
        }
        try {
            String sid = extractSteamId(data.serverIP);
            long steamId = Long.parseLong(sid);
            String iconB64 = steambridge.steam.SteamSocial.ProfileCache.get().getAvatarIconB64(steamId);
            if (iconB64 != null && !iconB64.isEmpty()
                    && !iconB64.equals(data.getBase64EncodedIconData())) {
                data.setBase64EncodedIconData(iconB64);
            }
        } catch (Exception ignored) {
            // keep default icon until Steam has the avatar ready
        }
    }

    /** Public {@link GuiMultiplayer#getServerList()} (same role as getServers() on 1.16+). */
    private static ServerList getSavedServerList(GuiScreen gui) {
        if (!(gui instanceof GuiMultiplayer)) return null;
        try {
            return ((GuiMultiplayer) gui).getServerList();
        } catch (Exception e) {
            return null;
        }
    }

    /** Iterates the saved server list of {@code gui} and marks all Steam entries. */
    
    /**
     * Steam multiplayer-list polish throttle.
     * Immediate when the SteamID set changes; otherwise at most every 5s (avatars).
     */
    private static final long STEAM_LIST_MARK_INTERVAL_MS = 5000L;
    private static long lastSteamListMarkMs = 0L;
    private static String lastSteamListFingerprint = "";

    private static String steamListFingerprint(ServerList list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder(64);
        try {
            for (int i = 0; i < list.countServers(); i++) {
                ServerData data = list.getServerData(i);
                if (data == null || !isSteamServerId(data.serverIP)) continue;
                sb.append(extractSteamId(data.serverIP)).append('\n');
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

/** Marks Steam entries; force skips the 5s throttle (screen open). */
    private static void markAllSteamServers(GuiScreen gui) {
        markAllSteamServers(gui, false);
    }

    private static void markAllSteamServers(GuiScreen gui, boolean force) {
        try {
            ServerList list = getSavedServerList(gui);
            if (list == null) return;

            String fingerprint = steamListFingerprint(list);
            long now = System.currentTimeMillis();
            boolean steamSetChanged = !fingerprint.equals(lastSteamListFingerprint);
            if (!force && !steamSetChanged
                    && (now - lastSteamListMarkMs) < STEAM_LIST_MARK_INTERVAL_MS) {
                return;
            }
            lastSteamListFingerprint = fingerprint;
            lastSteamListMarkMs = now;

            int steamIndex = 0;
            for (int i = 0; i < list.countServers(); i++) {
                ServerData data = list.getServerData(i);
                if (data == null) continue;
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
        GuiScreen gui = event.getGui();

        /*
         * Vanilla updateScreen() resets the button enabled-state every tick.
         * Re-enable Connect (id=1) every frame when the IP field is non-empty.
         */
        if (pendingConnectEnable || isDirectConnectWithAddress(gui)) {
            try {
                List<GuiButton> buttons = ReflectionHelper.getPrivateValue(GuiScreen.class, gui, "buttonList", "field_146292_n");
                if (buttons != null) {
                    for (GuiButton b : buttons) {
                        if (b.id == 1) {
                            b.enabled = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            pendingConnectEnable = false;
        }
    }

    @SubscribeEvent
    public static void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiScreen gui = event.getGui();
        if (gui instanceof GuiShareToLan) {
            String title = net.minecraft.client.resources.I18n.format("steambridge.gui.steam_settings");

            // Find the Game Mode button (id = 104) to anchor our text
            int textY = gui.height / 4 + 40; // fallback
            try {
                List<GuiButton> buttons = ReflectionHelper.getPrivateValue(GuiScreen.class, gui, "buttonList", "field_146292_n");
                if (buttons != null) {
                    for (GuiButton b : buttons) {
                        if (b.id == 104) {
                            textY = b.y + 28;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            
            gui.drawCenteredString(Minecraft.getMinecraft().fontRenderer, title, gui.width / 2, textY, 0xFFFFFF);
        }
    }

    /** Returns true when {@code gui} is a DirectConnect screen whose IP field is non-empty. */
    private static boolean isDirectConnectWithAddress(GuiScreen gui) {
        if (!(gui instanceof GuiScreenServerList)) return false;
        GuiTextField tf = findIpTextField(gui);
        return tf != null && !tf.getText().isEmpty();
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        GuiScreen next = event.getGui();
        Minecraft mc   = Minecraft.getMinecraft();

        if (next instanceof GuiShareToLan) {
            if (isSteamHostSessionActive(mc)) {
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
                final String steamAddr = serverData.serverIP;
                GuiScreen connectingParent = mc.currentScreen;
                if (fConnectingPreviousScreen != null) {
                    try {
                        GuiScreen p = (GuiScreen) fConnectingPreviousScreen.get(next);
                        if (p != null) connectingParent = p;
                    } catch (Exception ignored) {}
                }
                final GuiScreen parent = connectingParent;

                if (!SteamManager.getInstance().isInitialized()
                        && !SteamManager.getInstance().reinit()) {
                    SteamBridgeMod.LOG.info(
                            "Steam not running; opening launch screen before connect to {}", steamAddr);
                    event.setCanceled(true);
                    mc.displayGuiScreen(new GuiSteamResync(
                            parent,
                            () -> beginSteamConnect(parent, steamAddr),
                            "steambridge.gui.resync_success_hint_connect"));
                    return;
                }

                long steamId = Long.parseLong(extractSteamId(steamAddr));
                SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
                SteamClient active = SteamManager.getInstance().getActiveClient();
                if (active != null) active.disconnect();
                SteamClient client = new SteamClient();
                client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
                event.setCanceled(true);
                mc.displayGuiScreen(new GuiSteamConnecting(parent, client));
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
                // Signal the draw hook to keep Connect (id=1) enabled every frame.
                pendingConnectEnable = true;
                for (GuiButton b : event.getButtonList()) {
                    if (b.id == 1) { b.enabled = true; break; }
                }
            }
        }

        if (gui instanceof GuiShareToLan) {
            GuiButton startLan = null, cancel = null, gameMode = null;
            for (GuiButton b : event.getButtonList()) {
                if (b.id == 101) startLan = b;
                if (b.id == 102) cancel   = b;
                if (b.id == 104) gameMode = b;
            }
            if (startLan != null && cancel != null) {
                // Load saved settings for this world
                net.minecraft.server.integrated.IntegratedServer srv = Minecraft.getMinecraft().getIntegratedServer();
                if (srv != null) {
                    steambridge.steam.SteamSocial.Worlds.Settings saved =
                            steambridge.steam.SteamSocial.Worlds.get().load(srv.getFolderName());
                    pendingTransportMode = steambridge.steam.SteamSocial.Worlds.parseTransportMode(saved.transportMode);
                    pendingAccessPolicy  = steambridge.steam.SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);
                }

                // Steam settings row: sit directly below vanilla's Game Mode button
                FontRenderer font = Minecraft.getMinecraft().fontRenderer;
                int steamRowY = (gameMode != null) ? (gameMode.y + 40) : (gui.height / 4 + 55);
                String accessLabel = accessPolicyButtonLabel(pendingAccessPolicy);
                String routeLabel  = transportButtonLabel(pendingTransportMode);
                event.getButtonList().add(new GuiButton(BTN_ACCESS_POLICY,
                        gui.width / 2 - 155, steamRowY, 150, 20, accessLabel));
                event.getButtonList().add(new GuiButton(BTN_TRANSPORT_MODE,
                        gui.width / 2 + 5, steamRowY, 150, 20, routeLabel));

                // Bottom row: Start LAN + Open via Steam + Cancel (widths fit labels)
                int bottomY = gui.height - 28;
                int gap = 6;
                int leftEdge = gui.width / 2 - 155;
                int rightEdge = gui.width / 2 + 155;
                String openSteam = net.minecraft.client.resources.I18n.format("steambridge.gui.open_steam");
                String startMsg = startLan.displayString;
                String cancelMsg = cancel.displayString;
                int slotMax = 110;
                int startW = GuiButtons.fitWidth(font, startMsg, 80, slotMax);
                int steamW = GuiButtons.fitWidth(font, openSteam, 80, slotMax);
                int cancelW = GuiButtons.fitWidth(font, cancelMsg, 80, slotMax);
                int total = startW + steamW + cancelW + 2 * gap;
                int span = rightEdge - leftEdge;
                if (total > span) {
                    int over = total - span;
                    int each = (over + 2) / 3;
                    startW = Math.max(70, startW - each);
                    steamW = Math.max(70, steamW - each);
                    cancelW = Math.max(70, cancelW - each);
                    total = startW + steamW + cancelW + 2 * gap;
                }
                int x0 = leftEdge + Math.max(0, (span - total) / 2);
                startLan.width = startW;
                startLan.x = x0;
                startLan.y = bottomY;
                event.getButtonList().add(new GuiButton(BTN_STEAM_HOST,
                        x0 + startW + gap, bottomY, steamW, 20, openSteam));
                cancel.width = cancelW;
                cancel.x = x0 + startW + gap + steamW + gap;
                cancel.y = bottomY;
            }
        }

        // -- Friends button next to the server-address field -------------------
        if (gui instanceof GuiScreenAddServer || gui instanceof GuiScreenServerList) {
            GuiTextField ipField = findIpTextField(gui);
            if (ipField != null) {
                FontRenderer font = Minecraft.getMinecraft().fontRenderer;
                String friendsMsg = net.minecraft.client.resources.I18n.format("steambridge.gui.friends_short");
                event.getButtonList().add(GuiButtons.create(
                        BTN_FRIENDS, font, ipField.x + ipField.width + 4, ipField.y, friendsMsg, 20, 80));
            }
        }

        // -- Pre-mark Steam servers + schedule a second pass before first render -
        if (gui instanceof GuiMultiplayer) {
            markAllSteamServers(gui, true);
            // addScheduledTask runs before the very next render loop -> belt-and-suspenders
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().currentScreen == gui) markAllSteamServers(gui, true);
            });
        }

        // -- In-game menu: update "Open to LAN" button for Steam host ----------
        if (gui instanceof GuiIngameMenu) {
            Minecraft mc = Minecraft.getMinecraft();
            boolean isSteam = isSteamHostSessionActive(mc);
            boolean isLan   = mc.getIntegratedServer() != null && mc.getIntegratedServer().getPublic();
            for (GuiButton btn : event.getButtonList()) {
                if (btn.id == 7) {
                    // Always start from vanilla label so a previous session cannot stick.
                    btn.displayString = net.minecraft.client.resources.I18n.format("menu.shareToLan");
                    if (isSteam) {
                        btn.enabled = true;
                        btn.displayString = net.minecraft.client.resources.I18n.format("steambridge.gui.manage_session");
                    } else if (isLan) {
                        btn.displayString += net.minecraft.client.resources.I18n.format("steambridge.gui.lan_suffix");
                    }
                    break;
                }
            }
        }
    }

    /**
     * True only while this integrated world is actually open via Steam.
     * A leftover {@link SteamServer} from a previous world in the same JVM session
     * must not keep the pause-menu button on "Manage Steam Session".
     */
    private static boolean isSteamHostSessionActive(Minecraft mc) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return false;
        }
        net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
        if (integrated == null || !integrated.getPublic()) {
            return false;
        }
        try {
            String folder = integrated.getFolderName();
            if (folder != null && !folder.isEmpty()
                    && !folder.equals(server.getWorldKey())) {
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    @SubscribeEvent
    public static void onActionPerformedPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getGui() instanceof GuiIngameMenu && event.getButton().id == 7) {
            if (isSteamHostSessionActive(Minecraft.getMinecraft())) {
                event.setCanceled(true);
                Minecraft.getMinecraft().displayGuiScreen(new GuiSteamHostManagement(event.getGui()));
            }
        }
    }

    @SubscribeEvent
    public static void onActionPerformedPost(GuiScreenEvent.ActionPerformedEvent.Post event) {
        GuiScreen gui = event.getGui();
        GuiButton btn = event.getButton();

        // -- Cycle transport route (Share-to-LAN screen) -----------------------
        if (gui instanceof GuiShareToLan && btn.id == BTN_TRANSPORT_MODE) {
            pendingTransportMode = nextTransportMode(pendingTransportMode);
            btn.displayString = transportButtonLabel(pendingTransportMode);
            saveShareToLanSettings(gui);
            return;
        }

        // -- Toggle access policy (Share-to-LAN screen) ------------------------
        if (gui instanceof GuiShareToLan && btn.id == BTN_ACCESS_POLICY) {
            pendingAccessPolicy = (pendingAccessPolicy == SteamServer.AccessPolicy.EVERYONE)
                    ? SteamServer.AccessPolicy.FRIENDS_ONLY
                    : SteamServer.AccessPolicy.EVERYONE;
            btn.displayString = accessPolicyButtonLabel(pendingAccessPolicy);
            saveShareToLanSettings(gui);
            return;
        }

        // -- Open for Steam (in Share-to-LAN screen) ---------------------------
        if (gui instanceof GuiShareToLan && btn.id == BTN_STEAM_HOST) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getIntegratedServer() != null) {
                if (!SteamManager.getInstance().isInitialized()) {
                    if (!SteamManager.getInstance().reinit()) {
                        try {
                            SteamAppIdHelper.ensureAppId(mc.gameDir);
                            SteamAppIdHelper.launchSteam();
                        } catch (Exception e) {
                            SteamBridgeMod.LOG.warn("[SteamHost] Failed to launch Steam: {}", e.getMessage());
                        }
                        mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString(
                                net.minecraft.client.resources.I18n.format("steambridge.gui.host_steam_launching")));
                        return;
                    }
                }

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
                        pendingAccessPolicy,
                        pendingTransportMode);

                String port;
                try {
                    port = mc.getIntegratedServer().shareToLAN(gameType, ac);
                    SteamBridgeMod.LOG.info("[SteamHost] shareToLAN returned port={}", port);
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamHost] shareToLAN failed", e);
                    mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString(
                            "\u00A7c" + net.minecraft.client.resources.I18n.format("steambridge.gui.host_failed")));
                    return;
                }

                SteamServer server = new SteamServer(pendingAccessPolicy, worldKey, "World");
                server.setTransportMode(pendingTransportMode);
                if (port != null) {
                    try { server.setMcPort(Integer.parseInt(port)); }
                    catch (NumberFormatException ignored) {}
                }
                boolean started;
                try {
                    started = server.start();
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamHost] SteamServer.start failed", e);
                    started = false;
                }

                if (started) {
                    mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F));
                    mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString(
                            "\u00A7a" + net.minecraft.client.resources.I18n.format("steambridge.gui.host_started")));
                    mc.displayGuiScreen(null);
                    mc.setIngameFocus();
                } else {
                    mc.ingameGUI.getChatGUI().printChatMessage(new net.minecraft.util.text.TextComponentString(
                            "\u00A7c" + net.minecraft.client.resources.I18n.format("steambridge.gui.host_failed")));
                }
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

    /** Cycles AUTO -> P2P_ONLY -> RELAY_ONLY -> AUTO. */
    private static SteamServer.TransportMode nextTransportMode(SteamServer.TransportMode mode) {
        switch (mode) {
            case AUTO:      return SteamServer.TransportMode.P2P_ONLY;
            case P2P_ONLY:  return SteamServer.TransportMode.RELAY_ONLY;
            case RELAY_ONLY:
            default:        return SteamServer.TransportMode.AUTO;
        }
    }

    private static String transportButtonLabel(SteamServer.TransportMode mode) {
        String key;
        switch (mode) {
            case P2P_ONLY:   key = "steambridge.gui.route_p2p";   break;
            case RELAY_ONLY: key = "steambridge.gui.route_relay"; break;
            case AUTO:
            default:         key = "steambridge.gui.route_auto";  break;
        }
        return net.minecraft.client.resources.I18n.format(key);
    }

    private static String accessPolicyButtonLabel(SteamServer.AccessPolicy policy) {
        String key = (policy == SteamServer.AccessPolicy.EVERYONE)
                ? "steambridge.gui.access_everyone"
                : "steambridge.gui.access_friends";
                
        return net.minecraft.client.resources.I18n.format(key);
    }

    private static void saveShareToLanSettings(GuiScreen gui) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getIntegratedServer() == null) return;

        boolean ac = false;
        String gm  = "survival";
        try {
            if (fShareToLanGameMode != null)       gm = (String) fShareToLanGameMode.get(gui);
            if (fShareToLanAllowCommands != null)  ac = (Boolean) fShareToLanAllowCommands.get(gui);
        } catch (Exception ignored) {}

        net.minecraft.world.GameType gameType =
                net.minecraft.world.GameType.parseGameTypeWithDefault(gm, net.minecraft.world.GameType.SURVIVAL);
        String worldKey = mc.getIntegratedServer().getFolderName();

        steambridge.steam.SteamSocial.Worlds.get().save(
                worldKey, gameType, ac,
                pendingAccessPolicy,
                pendingTransportMode);
    }
}

