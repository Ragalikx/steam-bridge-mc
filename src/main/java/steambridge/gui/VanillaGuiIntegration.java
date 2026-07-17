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
import steambridge.steam.SteamSocial;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.ServerListScreen;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.HTTPUtil;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameType;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = SteamBridgeMod.MODID)
public final class VanillaGuiIntegration {

    private VanillaGuiIntegration() {}

    /** Transport route the host will start with, cycled by the button on the Share-to-LAN screen. */
    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    /** Access policy (Friends/Everyone), toggled by the button on the Share-to-LAN screen. */
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;
    /** Pending SteamID to inject when the parent screen re-initializes. */
    static String pendingSteamId = null;
    /** Pending server name to restore in AddServerScreen after picking a friend. */
    static String pendingServerName = null;

    // -- Steam-ID helpers ------------------------------------------------------

    private static boolean isSteamServerId(String ip) {
        if (ip == null) return false;
        String host = ip.trim();
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.length() >= 15 && host.startsWith("7656119") && host.matches("\\d+");
    }

    private static String extractSteamId(String ip) {
        String host = ip.trim();
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    // -- Type-based reflection (no SRG names) ----------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T findByType(Object owner, Class<T> type) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) {
                try { f.setAccessible(true); Object v = f.get(owner); if (v != null) return (T) v; }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean findPrimitiveBoolean(Object owner) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (f.getType() == boolean.class) {
                try { f.setAccessible(true); return (boolean) f.get(owner); }
                catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static void setByType(Object owner, Class<?> type, Object value) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) {
                try { f.setAccessible(true); f.set(owner, value); return; }
                catch (Exception ignored) {}
            }
        }
    }

    private static List<TextFieldWidget> findEditBoxes(Screen gui) {
        List<TextFieldWidget> boxes = new ArrayList<>();
        for (Field f : gui.getClass().getDeclaredFields()) {
            if (TextFieldWidget.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    TextFieldWidget e = (TextFieldWidget) f.get(gui);
                    if (e != null) boxes.add(e);
                } catch (Exception ignored) {}
            }
        }
        return boxes;
    }

    /**
     * The server-address field. On {@link AddServerScreen} it is typically the first
     * TextFieldWidget; on {@link ServerListScreen} (direct connect) there is only one.
     */
    private static TextFieldWidget findIpEditBox(Screen gui) {
        List<TextFieldWidget> boxes = findEditBoxes(gui);
        if (gui instanceof AddServerScreen && !boxes.isEmpty()) return boxes.get(0);
        return boxes.isEmpty() ? null : boxes.get(boxes.size() - 1);
    }

    private static TextFieldWidget findNameEditBox(Screen gui) {
        List<TextFieldWidget> boxes = findEditBoxes(gui);
        return (gui instanceof AddServerScreen && boxes.size() >= 2) ? boxes.get(1) : null;
    }

    // -- Vanilla-button lookup by message --------------------------------------

    private static Button findButtonByMessage(GuiScreenEvent.InitGuiEvent event, String translationKey) {
        String want = I18n.get(translationKey);
        for (Widget w : event.getWidgetList()) {
            if (w instanceof Button) {
                Button b = (Button) w;
                if (b.getMessage().getString().equals(want)) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Wraps a live {@link Button}'s {@code onPress} callback in place (found via type-based
     * reflection). Lets us intercept a vanilla button without touching position/active state.
     */
    private static void wrapOnPress(Button button, java.util.function.Function<Button.IPressable, Button.IPressable> wrapper) {
        for (Field f : Button.class.getDeclaredFields()) {
            if (Button.IPressable.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Button.IPressable original = (Button.IPressable) f.get(button);
                    f.set(button, wrapper.apply(original));
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to wrap button onPress", e);
                }
                return;
            }
        }
    }

    /**
     * Starts a Steam P2P connect and returns the status screen. Does not call
     * {@link Minecraft#setScreen} so callers can either set it themselves or inject it via
     * {@link GuiOpenEvent#setGui}.
     */
    private static GuiSteamConnecting beginSteamConnect(Screen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) active.disconnect();
        SteamClient client = new SteamClient();
        client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
        return new GuiSteamConnecting(parent, client);
    }

    private static void interceptSteamConnect(Screen parent, String steamAddr) {
        Minecraft mc = Minecraft.getInstance();
        if (!SteamManager.getInstance().isInitialized()) {
            if (!SteamManager.getInstance().reinit()) {
                SteamBridgeMod.LOG.info(
                        "Steam not running; opening launch screen before connect to {}", steamAddr);
                mc.setScreen(new GuiSteamResync(
                        parent,
                        () -> mc.setScreen(beginSteamConnect(parent, steamAddr)),
                        "steambridge.gui.resync_success_hint_connect"));
                return;
            }
        }
        mc.setScreen(beginSteamConnect(parent, steamAddr));
    }

    /**
     * {@link ConnectingScreen} starts a DNS/TCP thread even if GuiOpenEvent replaces the screen.
     * Setting every boolean field true aborts that race ("Unknown host" disconnect).
     */
    private static void abortVanillaConnect(ConnectingScreen screen) {
        boolean set = false;
        for (Field f : ConnectingScreen.class.getDeclaredFields()) {
            if (f.getType() != boolean.class && f.getType() != Boolean.class) continue;
            try {
                f.setAccessible(true);
                f.setBoolean(screen, true);
                set = true;
            } catch (Exception ignored) {}
        }
        if (!set) {
            SteamBridgeMod.LOG.warn("Could not abort ConnectingScreen (no boolean field found)");
        }
    }

    private static Screen connectScreenParent(ConnectingScreen screen) {
        for (Field f : ConnectingScreen.class.getDeclaredFields()) {
            if (!Screen.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object p = f.get(screen);
                if (p instanceof Screen) return (Screen) p;
            } catch (Exception ignored) {}
        }
        return Minecraft.getInstance().screen;
    }

    // -- Steam-server marking (ping suppression + null MOTD safety) ------------

    private static void sanitizeServerDataFields(ServerData data) {
        if (data.motd == null) {
            data.motd = StringTextComponent.EMPTY;
        }
        if (data.status == null) {
            data.status = StringTextComponent.EMPTY;
        }
        if (data.version == null) {
            data.version = new StringTextComponent("???");
        }
        if (data.playerList == null) {
            data.playerList = java.util.Collections.emptyList();
        }
    }

    /** Skip TCP ping for a SteamID entry and give it safe display components + avatar icon. */
    private static void markSteamServer(ServerData data) {
        data.pinged = true;
        data.ping = 0L;
        data.motd = new TranslationTextComponent("steambridge.gui.server_steam_motd");
        data.status = new TranslationTextComponent("steambridge.gui.server_steam_status");
        if (data.version == null) {
            data.version = new StringTextComponent("Steam");
        }
        if (data.playerList == null) {
            data.playerList = java.util.Collections.emptyList();
        }

        try {
            long steamId = Long.parseLong(extractSteamId(data.ip));
            String iconB64 = SteamSocial.ProfileCache.get().getAvatarIconB64(steamId);
            if (iconB64 != null && !iconB64.isEmpty()
                    && !iconB64.equals(data.getIconB64())) {
                data.setIconB64(iconB64);
            }
        } catch (Exception ignored) {
            // keep default icon until Steam has the avatar ready
        }
    }

    
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
            for (int i = 0; i < list.size(); i++) {
                ServerData data = list.get(i);
                if (data == null || !isSteamServerId(data.ip)) continue;
                sb.append(extractSteamId(data.ip)).append('\n');
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

private static void markAllSteamServers(MultiplayerScreen gui) {
        markAllSteamServers(gui, false);
    }

    private static void markAllSteamServers(MultiplayerScreen gui, boolean force) {
        ServerList list = gui.getServers();
        if (list == null) return;
        try {
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
            for (int i = 0; i < list.size(); i++) {
                ServerData data = list.get(i);
                if (data == null) continue;
                sanitizeServerDataFields(data);
                if (isSteamServerId(data.ip)) {
                    markSteamServer(data);
                    if (i > steamIndex) list.swap(i, steamIndex);
                    steamIndex++;
                }
            }
        } catch (Exception ignored) {}
    }

    // -- Events ----------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MultiplayerScreen) {
            markAllSteamServers((MultiplayerScreen) mc.screen);
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        Screen next = event.getGui();
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectingScreen with a SteamID address:
        // bottom Join button, icon Play overlay, double-click, direct-join confirm.
        if (next instanceof ConnectingScreen) {
            ConnectingScreen connectScreen = (ConnectingScreen) next;
            ServerData sd = mc.getCurrentServer();
            if (sd != null && isSteamServerId(sd.ip)) {
                abortVanillaConnect(connectScreen);
                Screen parent = connectScreenParent(connectScreen);
                if (parent == null) parent = mc.screen;
                final Screen connectParent = parent;
                final String steamAddr = sd.ip;
                if (!SteamManager.getInstance().isInitialized() && !SteamManager.getInstance().reinit()) {
                      event.setGui(new GuiSteamResync(
                              connectParent,
                              () -> Minecraft.getInstance().setScreen(beginSteamConnect(connectParent, steamAddr)),
                              "steambridge.gui.resync_success_hint_connect"));
                  } else {
                      event.setGui(beginSteamConnect(connectParent, steamAddr));
                  }
                return;
            }
        }

        if (next instanceof ShareToLanScreen) {
            if (isSteamHostSessionActive(mc)) {
                event.setGui(new GuiSteamHostManagement(mc.screen));
                return;
            }
            if (mc.getSingleplayerServer() != null) {
                try {
                    String worldKey = worldKey(mc.getSingleplayerServer());
                    SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey);
                    setByType(next, GameType.class, SteamSocial.Worlds.parseGameType(saved.gametype));
                    setByType(next, boolean.class, Boolean.valueOf(saved.allowCommands));
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to load ShareToLan defaults", e);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onScreenInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        Screen gui = event.getGui();

        applyPendingSteamId(event, gui);
        injectShareToLanControls(event, gui);
        injectFriendsButton(event, gui);
        injectSteamConnectIntercept(event, gui);
        if (gui instanceof MultiplayerScreen) {
            markAllSteamServers((MultiplayerScreen) gui, true);
        }
        injectPauseMenuControl(event, gui);
    }

    private static void injectSteamConnectIntercept(GuiScreenEvent.InitGuiEvent.Post event, Screen gui) {
        if (gui instanceof ServerListScreen) {
            Button join = findButtonByMessage(event, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                TextFieldWidget ip = findIpEditBox(gui);
                String addr = ip != null ? ip.getValue() : "";
                if (isSteamServerId(addr)) {
                    Screen last = findByType(gui, Screen.class);
                    interceptSteamConnect(last != null ? last : gui, addr);
                } else {
                    original.onPress(b);
                }
            });
        } else if (gui instanceof MultiplayerScreen) {
            final MultiplayerScreen mps = (MultiplayerScreen) gui;
            Button join = findButtonByMessage(event, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                ServerData selected = findByType(gui, ServerData.class);
                if (selected != null && isSteamServerId(selected.ip)) {
                    interceptSteamConnect(mps, selected.ip);
                } else {
                    original.onPress(b);
                }
            });
        }
    }

    // -- Injection helpers -----------------------------------------------------

    private static void applyPendingSteamId(GuiScreenEvent.InitGuiEvent event, Screen gui) {
        if (pendingSteamId == null) return;
        final String sid  = pendingSteamId;
        final String name = pendingServerName;
        pendingSteamId    = null;
        pendingServerName = null;

        if (gui instanceof AddServerScreen) {
            ServerData sd = findByType(gui, ServerData.class);
            if (sd != null) sd.ip = sid;
            TextFieldWidget ip = findIpEditBox(gui);
            if (ip != null) ip.setValue(sid);
            if (name != null) {
                TextFieldWidget nameBox = findNameEditBox(gui);
                if (nameBox != null) nameBox.setValue(name);
            }
        } else {
            TextFieldWidget tf = findIpEditBox(gui);
            if (tf != null) tf.setValue(sid);
        }
    }

    private static void injectShareToLanControls(GuiScreenEvent.InitGuiEvent.Post event, Screen gui) {
        if (!(gui instanceof ShareToLanScreen)) return;

        IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
        if (srv != null) {
            SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey(srv));
            pendingTransportMode = SteamSocial.Worlds.parseTransportMode(saved.transportMode);
            pendingAccessPolicy  = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);

            // ShareToLan init overwrites commands from level.dat. If saved value differs, press once.
            if (saved.allowCommands != findPrimitiveBoolean(gui)) {
                String commandsLabel = I18n.get("selectWorld.allowCommands");
                for (Widget w : event.getWidgetList()) {
                    if (w instanceof Button) {
                        Button btn = (Button) w;
                        if (btn.getMessage().getString().contains(commandsLabel)) {
                            btn.onPress();
                            break;
                        }
                    }
                }
            }
        }

        // Layout (same idea as 1.12.2 / 1.19.2):
        //   vanilla game mode + commands
        //   section title "Steam session settings" (drawn in onShareToLanRender)
        //   access + route row
        //   bottom row: [Start LAN] [Open via Steam] [Cancel]
        FontRenderer font = Minecraft.getInstance().font;
        int steamOptsY = 140;

        ITextComponent accessMsg = new StringTextComponent(accessPolicyLabel(pendingAccessPolicy));
        ITextComponent routeMsg  = new StringTextComponent(transportLabel(pendingTransportMode));

        event.addWidget(new Button(gui.width / 2 - 155, steamOptsY, 150, GuiButtons.HEIGHT,
                accessMsg, b -> {
            pendingAccessPolicy = (pendingAccessPolicy == SteamServer.AccessPolicy.EVERYONE)
                    ? SteamServer.AccessPolicy.FRIENDS_ONLY : SteamServer.AccessPolicy.EVERYONE;
            b.setMessage(new StringTextComponent(accessPolicyLabel(pendingAccessPolicy)));
            saveShareToLanSettings(gui);
        }));

        event.addWidget(new Button(gui.width / 2 + 5, steamOptsY, 150, GuiButtons.HEIGHT,
                routeMsg, b -> {
            pendingTransportMode = nextTransportMode(pendingTransportMode);
            b.setMessage(new StringTextComponent(transportLabel(pendingTransportMode)));
            saveShareToLanSettings(gui);
        }));

        Button startLan = findButtonByMessage(event, "lanServer.start");
        Button cancel   = findButtonByMessage(event, "gui.cancel");
        int bottomY = gui.height - 28;
        int gap = 6;
        int leftEdge = gui.width / 2 - 155;
        int rightEdge = gui.width / 2 + 155;

        ITextComponent openSteamMsg = new TranslationTextComponent("steambridge.gui.open_steam");
        ITextComponent startMsg = startLan != null ? startLan.getMessage() : new TranslationTextComponent("lanServer.start");
        ITextComponent cancelMsg = cancel != null ? cancel.getMessage() : new TranslationTextComponent("gui.cancel");

        int slotMax = 110;
        int startW = GuiButtons.fitWidth(font, startMsg, 80, slotMax);
        int steamW = GuiButtons.fitWidth(font, openSteamMsg, 80, slotMax);
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

        if (startLan != null) {
            startLan.setWidth(startW);
            startLan.x = x0;
            startLan.y = bottomY;
        }
        if (cancel != null) {
            cancel.setWidth(cancelW);
            cancel.x = x0 + startW + gap + steamW + gap;
            cancel.y = bottomY;
        }
        event.addWidget(new Button(x0 + startW + gap, bottomY, steamW, GuiButtons.HEIGHT,
                openSteamMsg, b -> startSteamHost(gui)));
    }

    @SubscribeEvent
    public static void onShareToLanRender(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.getGui() instanceof ShareToLanScreen)) return;
        ShareToLanScreen gui = (ShareToLanScreen) event.getGui();
        int titleY = 128;
        String title = I18n.get("steambridge.gui.steam_settings");
        MatrixStack matrix = event.getMatrixStack();
        FontRenderer font = Minecraft.getInstance().font;
        int x = gui.width / 2 - font.width(title) / 2;
        font.draw(matrix, title, x, titleY, 0xFFFFFF);
    }

    private static void injectFriendsButton(GuiScreenEvent.InitGuiEvent.Post event, Screen gui) {
        if (!(gui instanceof AddServerScreen || gui instanceof ServerListScreen)) return;
        TextFieldWidget ip = findIpEditBox(gui);
        if (ip == null) return;

        FontRenderer font = Minecraft.getInstance().font;
        ITextComponent friendsMsg = new TranslationTextComponent("steambridge.gui.friends_short");
        event.addWidget(GuiButtons.create(font, ip.x + ip.getWidth() + 4, ip.y, friendsMsg, b -> {
            Minecraft mc = Minecraft.getInstance();
            if (gui instanceof AddServerScreen) {
                TextFieldWidget nameBox = findNameEditBox(gui);
                pendingServerName = nameBox != null ? nameBox.getValue() : null;
            } else {
                pendingServerName = null;
            }
            if (SteamManager.getInstance().isInitialized()) {
                mc.setScreen(new GuiSteamFriends(gui, null, steamId -> pendingSteamId = steamId));
            } else {
                mc.setScreen(new GuiSteamResync(gui, steamId -> pendingSteamId = steamId));
            }
        }, 20, 80));
    }

    /**
     * After the world is open on Steam, vanilla disables {@code menu.shareToLan}
     * ({@code isPublished() == true}). Forge 1.16.5 also has no "Mods" row on this screen
     * (unlike 1.19+), so the 1.19.2 "insert above Mods" trick finds nothing.
     * <p>
     * Match 1.12.2: repurpose the gray "Open to LAN" button into "Manage Steam session".
     * If that label is missing (modded pause menus), fall back to inserting a new button
     * above Return to Menu / Disconnect.
     */
    private static void injectPauseMenuControl(GuiScreenEvent.InitGuiEvent.Post event, Screen gui) {
        if (!(gui instanceof IngameMenuScreen)) return;
        if (!isSteamHostSessionActive(Minecraft.getInstance())) return;

        FontRenderer font = Minecraft.getInstance().font;
        ITextComponent manageMsg = new TranslationTextComponent("steambridge.gui.manage_session");

        Button shareToLan = findButtonByMessage(event, "menu.shareToLan");
        if (shareToLan != null) {
            // Keep the vanilla 98px dual-column slot - do not grow past neighbors.
            shareToLan.active = true;
            shareToLan.setMessage(manageMsg);
            wrapOnPress(shareToLan, original -> b ->
                    Minecraft.getInstance().setScreen(new GuiSteamHostManagement(gui)));
            return;
        }

        // Fallback for heavily modded pause menus without shareToLan.
        Button anchor = findButtonByMessage(event, "menu.returnToMenu");
        if (anchor == null) {
            anchor = findButtonByMessage(event, "menu.disconnect");
        }
        if (anchor == null) {
            SteamBridgeMod.LOG.warn(
                    "[SteamBridge] Pause menu: no shareToLan/returnToMenu to attach manage-session control.");
            return;
        }

        int manageW = GuiButtons.fitWidth(font, manageMsg, 98, Math.min(204, gui.width - 20));
        int x = gui.width / 2 - manageW / 2;
        int y = anchor.y;
        final int rowShift = 24;
        for (Widget w : event.getWidgetList()) {
            if (w instanceof Button) {
                Button b = (Button) w;
                if (b.y >= y) {
                    b.y += rowShift;
                }
            }
        }
        event.addWidget(new Button(x, y, manageW, GuiButtons.HEIGHT, manageMsg,
                b -> Minecraft.getInstance().setScreen(new GuiSteamHostManagement(gui))));
    }

    // -- Host start ------------------------------------------------------------

    private static void startSteamHost(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;

        if (!SteamManager.getInstance().isInitialized()) {
            if (!SteamManager.getInstance().reinit()) {
                try {
                    SteamAppIdHelper.ensureAppId(mc.gameDirectory);
                    SteamAppIdHelper.launchSteam();
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("[SteamHost] Failed to launch Steam: {}", e.getMessage());
                }
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            new StringTextComponent("\u00a7e" + I18n.get("steambridge.gui.host_steam_launching")), false);
                }
                return;
            }
        }

        GameType gameType = findByType(gui, GameType.class);
        if (gameType == null) gameType = GameType.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, gameType, commands, pendingAccessPolicy, pendingTransportMode);

        int port = HTTPUtil.getAvailablePort();
        boolean published = srv.publishServer(gameType, commands, port);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey, "World");
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (mc.player != null) {
            if (server.isRunning()) {
                mc.player.displayClientMessage(
                        new StringTextComponent("\u00a7a" + I18n.get("steambridge.gui.host_started")), false);
            } else {
                mc.player.displayClientMessage(
                        new StringTextComponent("\u00a7c" + I18n.get("steambridge.gui.host_failed")), false);
            }
        }
        mc.setScreen(null);
    }

    private static void saveShareToLanSettings(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;
        GameType gameType = findByType(gui, GameType.class);
        if (gameType == null) gameType = GameType.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);
        SteamSocial.Worlds.get().save(worldKey(srv), gameType, commands, pendingAccessPolicy, pendingTransportMode);
    }

    // -- Labels ----------------------------------------------------------------

    private static SteamServer.TransportMode nextTransportMode(SteamServer.TransportMode mode) {
        switch (mode) {
            case AUTO:      return SteamServer.TransportMode.P2P_ONLY;
            case P2P_ONLY:  return SteamServer.TransportMode.RELAY_ONLY;
            case RELAY_ONLY:
            default:        return SteamServer.TransportMode.AUTO;
        }
    }

    private static String transportLabel(SteamServer.TransportMode mode) {
        switch (mode) {
            case P2P_ONLY:   return I18n.get("steambridge.gui.route_p2p");
            case RELAY_ONLY: return I18n.get("steambridge.gui.route_relay");
            case AUTO:
            default:         return I18n.get("steambridge.gui.route_auto");
        }
    }

    private static String accessPolicyLabel(SteamServer.AccessPolicy policy) {
        return I18n.get(policy == SteamServer.AccessPolicy.EVERYONE
                ? "steambridge.gui.access_everyone" : "steambridge.gui.access_friends");
    }

    /** World folder name - used as the stable per-world settings/ban key. */
    private static String worldKey(IntegratedServer srv) {
        try {
            return srv.getWorldPath(FolderName.ROOT).getParent().getFileName().toString();
        } catch (Exception e) {
            return "__default_world__";
        }
    }

    private static boolean isSteamHostSessionActive(Minecraft mc) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return false;
        }
        IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated == null) {
            return false;
        }
        try {
            String folder = worldKey(integrated);
            String hosted = server.getWorldKey();
            if (folder != null && !folder.isEmpty()
                    && hosted != null && !hosted.isEmpty()
                    && !hosted.equals("__default_world__")
                    && !folder.equals(hosted)) {
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }
}
