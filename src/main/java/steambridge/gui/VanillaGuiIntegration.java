/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
public final class VanillaGuiIntegration {

    private VanillaGuiIntegration() {}

    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;
    static String pendingSteamId = null;
    static String pendingServerName = null;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.screen instanceof JoinMultiplayerScreen jms) {
                markAllSteamServers(jms);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            onScreenInit(screen);
            if (screen instanceof ShareToLanScreen) {
                ScreenEvents.remove(screen).register(VanillaGuiIntegration::saveShareToLanSettings);
            }
        });
    }

    /**
     * Called from {@code Minecraft.setScreen} mixin (like NeoForge ScreenEvent.Opening).
     * @return replacement screen, or the same instance to proceed
     */
    public static Screen onSetScreen(Screen next) {
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectScreen with a SteamID address:
        // bottom Join/Connect, icon Play overlay, double-click, direct-join confirm.
        // Button wrap alone is not enough (list rows call joinSelectedServer() directly).
        if (next instanceof ConnectScreen connectScreen) {
            // getCurrentServer() is usually null here — use list selection / direct-join field.
            // During setScreen HEAD, mc.screen is still the previous multiplayer/direct GUI.
            String addr = resolveSteamConnectAddress(mc.screen, mc);
            if (addr != null) {
                abortVanillaConnect(connectScreen);
                Screen parent = connectScreenParent(connectScreen);
                if (parent == null) parent = mc.screen;
                final Screen p = parent;
                final String steamAddr = addr;
                SteamBridgeMod.LOG.info(
                        "Rewriting ConnectScreen -> Steam for {} (previous={})",
                        steamAddr, mc.screen != null ? mc.screen.getClass().getSimpleName() : "null");
                if (!SteamManager.getInstance().isInitialized()
                        && !SteamManager.getInstance().reinit()) {
                    SteamBridgeMod.LOG.info(
                            "Steam not running; opening launch screen before connect to {}", steamAddr);
                    return new GuiSteamResync(
                            p,
                            () -> Minecraft.getInstance().setScreen(beginSteamConnect(p, steamAddr)),
                            "steambridge.gui.resync_success_hint_connect");
                }
                return beginSteamConnect(p, steamAddr);
            }
        }

        if (next instanceof ShareToLanScreen) {
            if (isSteamHostSessionActive(mc)) {
                return new GuiSteamHostManagement(mc.screen);
            }
        }
        return next;
    }

    private static void onScreenInit(Screen gui) {
        applyPendingSteamId(gui);
        injectShareToLanControls(gui);
        injectFriendsButton(gui);
        injectSteamConnectIntercept(gui);
        if (gui instanceof JoinMultiplayerScreen jms) markAllSteamServers(jms, true);
        injectPauseMenuControl(gui);
    }

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

    /**
     * ShareToLanScreen.commands (single primitive boolean on this screen).
     */
    private static boolean getShareToLanCommands(Screen gui) {
        for (Field f : gui.getClass().getDeclaredFields()) {
            if (f.getType() == boolean.class) {
                try {
                    f.setAccessible(true);
                    return f.getBoolean(gui);
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static void setShareToLanCommands(Screen gui, boolean value) {
        for (Field f : gui.getClass().getDeclaredFields()) {
            if (f.getType() == boolean.class) {
                try {
                    f.setAccessible(true);
                    f.setBoolean(gui, value);
                    return;
                } catch (Exception ignored) {}
            }
        }
    }

    private static GameType getShareToLanGameType(Screen gui) {
        GameType gt = findByType(gui, GameType.class);
        return gt != null ? gt : GameType.SURVIVAL;
    }

    private static void setShareToLanGameType(Screen gui, GameType value) {
        if (value == null) return;
        setByType(gui, GameType.class, value);
    }

    private static void setByType(Object owner, Class<?> type, Object value) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType()) || (type == boolean.class && f.getType() == boolean.class)) {
                try { f.setAccessible(true); f.set(owner, value); return; }
                catch (Exception ignored) {}
            }
        }
    }

    /** After init(), force ShareToLan CycleButtons + fields to match saved host settings. */
    @SuppressWarnings("unchecked")
    private static void applySavedShareToLan(Screen gui, SteamSocial.Worlds.Settings saved) {
        if (saved == null) return;
        GameType gameType = SteamSocial.Worlds.parseGameType(saved.gametype);
        setShareToLanGameType(gui, gameType);
        setShareToLanCommands(gui, saved.allowCommands);

        for (GuiEventListener l : gui.children()) {
            if (!(l instanceof CycleButton<?> raw)) continue;
            Object val = raw.getValue();
            if (val instanceof Boolean) {
                ((CycleButton<Boolean>) raw).setValue(saved.allowCommands);
            } else if (val instanceof GameType) {
                ((CycleButton<GameType>) raw).setValue(gameType);
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

    private static Button findButtonByMessage(Screen gui, String translationKey) {
        String want = I18n.get(translationKey);
        for (GuiEventListener l : gui.children()) {
            if (l instanceof Button b && b.getMessage().getString().equals(want)) {
                return b;
            }
        }
        return null;
    }

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

    private static void addButton(Screen gui, Button button) {
        Screens.getButtons(gui).add(button);
    }

    private static void interceptSteamConnect(Screen parent, String steamAddr) {
        Minecraft mc = Minecraft.getInstance();
        if (!SteamManager.getInstance().isInitialized()) {
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
     * Returns the connecting screen so {@link #onSetScreen} can replace ConnectScreen cleanly.
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

    private static void markSteamServer(ServerData data) {
        data.pinged = true;
        data.ping = 0L;
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

    private static void injectSteamConnectIntercept(Screen gui) {
        if (gui instanceof DirectJoinServerScreen) {
            Button join = findButtonByMessage(gui, "selectServer.select");
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
            // Bottom-bar Join/Connect. Play + double-click: onSetScreen ConnectScreen intercept.
            Button join = findButtonByMessage(gui, "selectServer.select");
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

    private static void applyPendingSteamId(Screen gui) {
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

    private static void injectShareToLanControls(Screen gui) {
        if (!(gui instanceof ShareToLanScreen)) return;

        IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
        if (srv != null) {
            SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey(srv));
            pendingTransportMode = SteamSocial.Worlds.parseTransportMode(saved.transportMode);
            pendingAccessPolicy  = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);
            // Restore after init() (it overwrites gameMode/commands from world data).
            applySavedShareToLan(gui, saved);
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

        addButton(gui, access);
        addButton(gui, transport);

        Button startLan = findButtonByMessage(gui, "lanServer.start");
        Button cancel   = findButtonByMessage(gui, "gui.cancel");
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
        addButton(gui, Button.builder(Component.translatable("steambridge.gui.open_steam"),
                b -> startSteamHost(gui)).bounds(gui.width / 2 - 48, bottomY, 96, 20).build());
    }

    private static void injectFriendsButton(Screen gui) {
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
        addButton(gui, friends);
    }

    private static void injectPauseMenuControl(Screen gui) {
        if (!(gui instanceof PauseScreen)) return;
        if (!isSteamHostSessionActive(Minecraft.getInstance())) return;

        // Own full-width row under "Back to Game". Shift every widget on/after that Y
        // down so Feedback / Report / Options / Disconnect keep their own slots.
        final int rowStep = 24; // 20px button + 4px gap
        Button returnToGame = findButtonByMessage(gui, "menu.returnToGame");
        Button options = findButtonByMessage(gui, "menu.options");

        int x;
        int w;
        int insertY;
        if (returnToGame != null) {
            x = returnToGame.getX();
            w = returnToGame.getWidth();
            insertY = returnToGame.getY() + returnToGame.getHeight() + 4;
        } else if (options != null) {
            w = 204;
            x = gui.width / 2 - 102;
            insertY = options.getY();
        } else {
            x = gui.width / 2 - 102;
            w = 204;
            insertY = gui.height / 4 + 48;
        }

        for (GuiEventListener listener : List.copyOf(gui.children())) {
            if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.getY() >= insertY) {
                widget.setY(widget.getY() + rowStep);
            }
        }

        addButton(gui, Button.builder(Component.translatable("steambridge.gui.manage_session"),
                b -> Minecraft.getInstance().setScreen(new GuiSteamHostManagement(gui)))
                .bounds(x, insertY, w, 20).build());
    }

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
                            Component.translatable("steambridge.gui.host_steam_launching").withStyle(ChatFormatting.YELLOW), false);
                }
                return;
            }
        }

        GameType gameType = getShareToLanGameType(gui);
        boolean commands = getShareToLanCommands(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, gameType, commands, pendingAccessPolicy, pendingTransportMode);

        int port = HttpUtil.getAvailablePort();
        boolean published = srv.publishServer(gameType, commands, port);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey);
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (mc.player != null) {
            if (server.isRunning()) {
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_started").withStyle(ChatFormatting.GREEN), false);
            } else {
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_failed").withStyle(ChatFormatting.RED), false);
            }
        }
        mc.setScreen(null);
    }

    private static void saveShareToLanSettings(Screen gui) {
        if (!(gui instanceof ShareToLanScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;
        GameType gameType = getShareToLanGameType(gui);
        boolean commands = getShareToLanCommands(gui);
        SteamSocial.Worlds.get().save(worldKey(srv), gameType, commands, pendingAccessPolicy, pendingTransportMode);
    }

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
            return srv.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
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
