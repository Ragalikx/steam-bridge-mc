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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
@EventBusSubscriber(modid = SteamBridgeMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class VanillaGuiIntegration {

    private VanillaGuiIntegration() {}

    /** Transport route the host will start with, cycled by the button on the Share-to-LAN screen. */
    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    /** Access policy (Friends/Everyone), toggled by the button on the Share-to-LAN screen. */
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;
    /** Pending SteamID to inject when the parent screen re-initializes. */
    static String pendingSteamId = null;
    /** Pending server name to restore in EditServerScreen after picking a friend. */
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

    // -- Type-based reflection (no obfuscated names) ---------------------------

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

    private static List<EditBox> findEditBoxes(Screen gui) {
        List<EditBox> boxes = new ArrayList<>();
        for (Field f : gui.getClass().getDeclaredFields()) {
            if (EditBox.class.isAssignableFrom(f.getType())) {
                try { f.setAccessible(true); EditBox e = (EditBox) f.get(gui); if (e != null) boxes.add(e); }
                catch (Exception ignored) {}
            }
        }
        return boxes;
    }

    private static EditBox findIpEditBox(Screen gui) {
        List<EditBox> boxes = findEditBoxes(gui);
        if (gui instanceof EditServerScreen && !boxes.isEmpty()) return boxes.get(0);
        return boxes.isEmpty() ? null : boxes.get(boxes.size() - 1);
    }

    private static EditBox findNameEditBox(Screen gui) {
        List<EditBox> boxes = findEditBoxes(gui);
        return (gui instanceof EditServerScreen && boxes.size() >= 2) ? boxes.get(1) : null;
    }

    // -- Vanilla-button lookup by message --------------------------------------

    private static Button findButtonByMessage(ScreenEvent.Init event, String translationKey) {
        String want = I18n.get(translationKey);
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.getMessage().getString().equals(want)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Wraps a live {@link Button}'s {@code onPress} callback in place via type-based
     * reflection. Lets us intercept a vanilla button's action without touching its
     * position/active/visible state.
     */
    private static void wrapOnPress(Button button, java.util.function.Function<Button.OnPress, Button.OnPress> wrapper) {
        for (Field f : Button.class.getDeclaredFields()) {
            if (Button.OnPress.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Button.OnPress original = (Button.OnPress) f.get(button);
                    f.set(button, wrapper.apply(original));
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to wrap button onPress", e);
                }
                return;
            }
        }
    }

    /**
     * Join a SteamID-shaped address. If Steam is not up, show {@link GuiSteamResync}
     * ("Launching Steam...") instead of failing later with a confusing relay error.
     */
    private static void interceptSteamConnect(Screen parent, String steamAddr) {
        Minecraft mc = Minecraft.getInstance();
        if (!SteamManager.getInstance().isInitialized()) {
            // Steam may have been started after the game; try once before showing the wait UI.
            if (!SteamManager.getInstance().reinit()) {
                SteamBridgeMod.LOG.info(
                        "Steam not running; opening launch screen before connect to {}", steamAddr);
                mc.setScreen(new GuiSteamResync(
                        parent,
                        () -> Minecraft.getInstance().setScreen(beginSteamConnect(parent, steamAddr)),
                        "steambridge.gui.resync_success_hint_connect"));
                return;
            }
        }
        mc.setScreen(beginSteamConnect(parent, steamAddr));
    }

    /**
     * Tear down any leftover Steam client and open {@link GuiSteamConnecting}.
     * Returns the connecting screen so {@link #onScreenOpening} can replace ConnectScreen cleanly.
     */
    private static GuiSteamConnecting beginSteamConnect(Screen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) {
            try {
                active.disconnect();
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn("[SteamClient] disconnect before reconnect: {}", e.getMessage());
            }
        }
        SteamManager.getInstance().setActiveClient(null);
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient();
        SteamClient client = new SteamClient();
        client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
        return new GuiSteamConnecting(parent, client);
    }

    /** Stop vanilla DNS/TCP thread when we replace ConnectScreen with Steam connect. */
    private static void abortVanillaConnect(ConnectScreen screen) {
        boolean set = false;
        for (Field f : ConnectScreen.class.getDeclaredFields()) {
            if (f.getType() != boolean.class && f.getType() != Boolean.class) continue;
            try {
                f.setAccessible(true);
                f.setBoolean(screen, true);
                set = true;
            } catch (Exception ignored) {}
        }
        if (!set) {
            SteamBridgeMod.LOG.warn("Could not abort ConnectScreen (no boolean field found)");
        }
    }

    private static Screen connectScreenParent(ConnectScreen screen) {
        for (Field f : ConnectScreen.class.getDeclaredFields()) {
            if (!Screen.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object p = f.get(screen);
                if (p instanceof Screen s) return s;
            } catch (Exception ignored) {}
        }
        return Minecraft.getInstance().screen;
    }

    /** Selected list entry (not {@code editingServer}, which findByType may hit first). */
    private static ServerData resolveSelectedServer(JoinMultiplayerScreen jms) {
        try {
            for (Field f : JoinMultiplayerScreen.class.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (!ft.getName().contains("ServerSelectionList")) continue;
                f.setAccessible(true);
                Object list = f.get(jms);
                if (list == null) continue;
                Object entry = null;
                for (Class<?> c = list.getClass(); c != null && entry == null; c = c.getSuperclass()) {
                    try {
                        java.lang.reflect.Method gm = c.getDeclaredMethod("getSelected");
                        gm.setAccessible(true);
                        entry = gm.invoke(list);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (entry == null) continue;
                try {
                    java.lang.reflect.Method gsd = entry.getClass().getMethod("getServerData");
                    Object sd = gsd.invoke(entry);
                    if (sd instanceof ServerData data) return data;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.debug("resolveSelectedServer failed: {}", e.toString());
        }
        return null;
    }

    // -- Steam-server marking (ping suppression) -------------------------------

    private static void markSteamServer(ServerData data) {
        data.ping = 0L;
        data.setState(ServerData.State.SUCCESSFUL);
        data.motd = Component.translatable("steambridge.gui.server_steam_motd");
        data.status = Component.translatable("steambridge.gui.server_steam_status");
        if (data.version == null) data.version = Component.literal("Steam");
        if (data.playerList == null) data.playerList = java.util.Collections.emptyList();
        try {
            long steamId = Long.parseLong(extractSteamId(data.ip));
            byte[] icon = SteamSocial.ProfileCache.get().getAvatarIconBytes(steamId);
            if (icon != null && icon.length > 0 && !java.util.Arrays.equals(icon, data.getIconBytes())) {
                data.setIconBytes(icon);
            }
        } catch (Exception ignored) {}
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

private static void markAllSteamServers(JoinMultiplayerScreen gui) {
        markAllSteamServers(gui, false);
    }

    private static void markAllSteamServers(JoinMultiplayerScreen gui, boolean force) {
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
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof JoinMultiplayerScreen jms) {
            markAllSteamServers(jms);
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectScreen with a SteamID address:
        // bottom Join/Connect, icon Play overlay, double-click, direct-join confirm.
        if (next instanceof ConnectScreen connectScreen) {
            ServerData sd = mc.getCurrentServer();
            if (sd != null && isSteamServerId(sd.ip)) {
                abortVanillaConnect(connectScreen);
                Screen parent = connectScreenParent(connectScreen);
                if (parent == null) parent = mc.screen;
                final Screen p = parent;
                final String addr = sd.ip;
                if (!SteamManager.getInstance().isInitialized()
                        && !SteamManager.getInstance().reinit()) {
                    SteamBridgeMod.LOG.info(
                            "Steam not running; opening launch screen before connect to {}", addr);
                    event.setNewScreen(new GuiSteamResync(
                            p,
                            () -> Minecraft.getInstance().setScreen(beginSteamConnect(p, addr)),
                            "steambridge.gui.resync_success_hint_connect"));
                } else {
                    event.setNewScreen(beginSteamConnect(p, addr));
                }
                return;
            }
        }

        if (next instanceof ShareToLanScreen) {
            if (isSteamHostSessionActive(mc)) {
                event.setNewScreen(new GuiSteamHostManagement(mc.screen));
                return;
            }
            if (mc.getSingleplayerServer() != null) {
                try {
                    String worldKey = worldKey(mc.getSingleplayerServer());
                    SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey);
                    setByType(next, GameType.class, SteamSocial.Worlds.parseGameType(saved.gametype));
                    setByType(next, boolean.class, saved.allowCommands);
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to load ShareToLan defaults", e);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen gui = event.getScreen();

        applyPendingSteamId(event, gui);
        injectShareToLanControls(event, gui);
        injectFriendsButton(event, gui);
        injectSteamConnectIntercept(event, gui);
        if (gui instanceof JoinMultiplayerScreen jms) markAllSteamServers(jms, true);
        injectPauseMenuControl(event, gui);
    }

    /**
     * Early wrap for bottom-bar Join/Connect. Play overlay + double-click go through
     * {@link JoinMultiplayerScreen#joinSelectedServer} and are caught in {@link #onScreenOpening}
     * when {@link ConnectScreen} opens (with abort of the leftover vanilla connector thread).
     */
    private static void injectSteamConnectIntercept(ScreenEvent.Init.Post event, Screen gui) {
        if (gui instanceof DirectJoinServerScreen) {
            Button join = findButtonByMessage(event, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                EditBox ip = findIpEditBox(gui);
                String addr = ip != null ? ip.getValue() : "";
                if (isSteamServerId(addr)) {
                    Screen last = findByType(gui, Screen.class);
                    interceptSteamConnect(last != null ? last : gui, addr);
                } else {
                    original.onPress(b);
                }
            });
        } else if (gui instanceof JoinMultiplayerScreen jms) {
            Button join = findButtonByMessage(event, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                ServerData selected = resolveSelectedServer(jms);
                if (selected != null && isSteamServerId(selected.ip)) {
                    interceptSteamConnect(jms, selected.ip);
                } else {
                    original.onPress(b);
                }
            });
        }
    }

    // -- Injection helpers -----------------------------------------------------

    private static void applyPendingSteamId(ScreenEvent.Init event, Screen gui) {
        if (pendingSteamId == null) return;
        final String sid  = pendingSteamId;
        final String name = pendingServerName;
        pendingSteamId    = null;
        pendingServerName = null;

        if (gui instanceof EditServerScreen) {
            ServerData sd = findByType(gui, ServerData.class);
            if (sd != null) sd.ip = sid;
            EditBox ip = findIpEditBox(gui);
            if (ip != null) ip.setValue(sid);
            if (name != null) {
                EditBox nameBox = findNameEditBox(gui);
                if (nameBox != null) nameBox.setValue(name);
            }
        } else {
            EditBox tf = findIpEditBox(gui);
            if (tf != null) tf.setValue(sid);
        }
    }

    private static void injectShareToLanControls(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof ShareToLanScreen)) return;

        IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
        SteamSocial.Worlds.Settings saved = null;
        if (srv != null) {
            saved = SteamSocial.Worlds.get().load(worldKey(srv));
            pendingTransportMode = SteamSocial.Worlds.parseTransportMode(saved.transportMode);
            pendingAccessPolicy  = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);

            // ShareToLanScreen.init() overwrites the commands field from world data
            // (level.dat allowCommands), discarding what onScreenOpening set.
            // Fix: if our saved value differs from what init() wrote, find the
            // commands CycleButton and press it once to toggle it to the right state.
            if (saved.allowCommands != findPrimitiveBoolean(gui)) {
                String commandsLabel = I18n.get("selectWorld.allowCommands.new");
                for (GuiEventListener l : event.getListenersList()) {
                    if (l instanceof CycleButton<?> btn
                            && btn.getMessage().getString().contains(commandsLabel)) {
                        btn.onPress();
                        break;
                    }
                }
            }
        }

        EditBox portEdit = findByType(gui, EditBox.class);
        int rowY = portEdit != null ? portEdit.getY() + portEdit.getHeight() + 20 : gui.height / 4 + 62;

        Button access = Button.builder(Component.literal(accessPolicyLabel(pendingAccessPolicy)), b -> {
            pendingAccessPolicy = (pendingAccessPolicy == SteamServer.AccessPolicy.EVERYONE)
                    ? SteamServer.AccessPolicy.FRIENDS_ONLY : SteamServer.AccessPolicy.EVERYONE;
            b.setMessage(Component.literal(accessPolicyLabel(pendingAccessPolicy)));
            saveShareToLanSettings(gui);
        }).bounds(gui.width / 2 - 155, rowY, 150, 20).build();

        Button transport = Button.builder(Component.literal(transportLabel(pendingTransportMode)), b -> {
            pendingTransportMode = nextTransportMode(pendingTransportMode);
            b.setMessage(Component.literal(transportLabel(pendingTransportMode)));
            saveShareToLanSettings(gui);
        }).bounds(gui.width / 2 + 5, rowY, 150, 20).build();

        event.addListener(access);
        event.addListener(transport);

        Button startLan = findButtonByMessage(event, "lanServer.start");
        Button cancel   = findButtonByMessage(event, "gui.cancel");
        int bottomY = gui.height - 28;
        if (startLan != null) {
            startLan.setWidth(96);
            startLan.setX(gui.width / 2 - 152);
            startLan.setY(bottomY);
        }
        if (cancel != null) {
            cancel.setWidth(96);
            cancel.setX(gui.width / 2 + 56);
            cancel.setY(bottomY);
        }
        event.addListener(Button.builder(Component.translatable("steambridge.gui.open_steam"),
                b -> startSteamHost(gui)).bounds(gui.width / 2 - 48, bottomY, 96, 20).build());
    }

    private static void injectFriendsButton(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof EditServerScreen || gui instanceof DirectJoinServerScreen)) return;
        EditBox ip = findIpEditBox(gui);
        if (ip == null) return;

        Button friends = Button.builder(Component.translatable("steambridge.gui.friends_short"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (gui instanceof EditServerScreen) {
                EditBox nameBox = findNameEditBox(gui);
                pendingServerName = nameBox != null ? nameBox.getValue() : null;
            } else {
                pendingServerName = null;
            }
            if (SteamManager.getInstance().isInitialized()) {
                mc.setScreen(new GuiSteamFriends(gui, null, steamId -> pendingSteamId = steamId));
            } else {
                mc.setScreen(new GuiSteamResync(gui, steamId -> pendingSteamId = steamId));
            }
        }).bounds(ip.getX() + ip.getWidth() + 4, ip.getY(), 20, 20).build();
        event.addListener(friends);
    }

    private static void injectPauseMenuControl(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof PauseScreen)) return;
        if (!isSteamHostSessionActive(Minecraft.getInstance())) return;

// Anchor a standalone "Manage Steam session" button directly above the NeoForge
        // "Mods" button. Once a world is already shared, vanilla removes the "Open to LAN"
        // entry, so that button is not available as an anchor. "Mods" is always present.
        // We do NOT hide or repurpose any vanilla button; only add our own widget and
        // nudge "Mods" and everything below it down one row.
        Button mods = findButtonByMessage(event, "fml.menu.mods");
        if (mods == null) return;

        int x = mods.getX(), y = mods.getY(), w = mods.getWidth();
        final int rowShift = 24;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.getY() >= y) {
                b.setY(b.getY() + rowShift);
            }
        }

        event.addListener(Button.builder(Component.translatable("steambridge.gui.manage_session"),
                b -> Minecraft.getInstance().setScreen(new GuiSteamHostManagement(gui)))
                .bounds(x, y, w, 20).build());
    }

    // -- Host start ------------------------------------------------------------

    private static void startSteamHost(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;

        if (!SteamManager.getInstance().isInitialized()) {
            // Try reinit first; covers the case where Steam was launched recently
            // but the mod hasn't detected it yet.
            if (!SteamManager.getInstance().reinit()) {
try {
                    SteamAppIdHelper.ensureAppId(mc.gameDirectory);
                    SteamAppIdHelper.launchSteam();
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("[SteamHost] Failed to launch Steam: {}", e.getMessage());
                }
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_steam_launching").withStyle(ChatFormatting.YELLOW), false);
                return;
            }
            // reinit succeeded: fall through and open the world
        }

        GameType gameType = findByType(gui, GameType.class);
        if (gameType == null) gameType = GameType.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, gameType, commands, pendingAccessPolicy, pendingTransportMode);

        int port = HttpUtil.getAvailablePort();
        boolean published = srv.publishServer(gameType, commands, port);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey);
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (server.isRunning()) {
            mc.player.displayClientMessage(
                    Component.translatable("steambridge.gui.host_started").withStyle(ChatFormatting.GREEN), false);
            mc.setScreen(null);
        } else {
            mc.player.displayClientMessage(
                    Component.translatable("steambridge.gui.host_failed").withStyle(ChatFormatting.RED), false);
            mc.setScreen(null);
        }
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

    private static String worldKey(IntegratedServer srv) {
        try {
            return srv.getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
        } catch (Exception e) {
            return "__default_world__";
        }
    }
    private static boolean isSteamHostSessionActive(Minecraft mc) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return false;
        }
        net.minecraft.client.server.IntegratedServer integrated = mc.getSingleplayerServer();
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
