/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;
import steambridge.steam.HostAuth;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;

import java.util.List;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
public final class VanillaGuiIntegration {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private VanillaGuiIntegration() {}

    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;
    static String pendingSteamId = null;
    static String pendingServerName = null;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    if (client.screen instanceof JoinMultiplayerScreen jms) {
                        markAllSteamServers(jms);
                    }
                });

        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) -> {
                    onScreenInit(screen);
                });
    }

    /**
     * Called from {@code Minecraft.setScreen} mixin (like NeoForge ScreenEvent.Opening).
     *
     * @return replacement screen, or the same instance to proceed
     */

    /**
     * SteamID for an upcoming vanilla connect. getCurrentServer() is often still null when
     * ConnectScreen opens (set only inside connect() after setScreen). Fall back to the selected
     * multiplayer-list row or Direct Join IP field - mc.screen is still the previous GUI.
     */
    private static String resolveSteamConnectAddress(Screen previousScreen, Minecraft mc) {
        try {
            ServerData cur = mc.getCurrentServer();
            if (cur != null && isSteamServerId(cur.ip)) {
                return cur.ip;
            }
        } catch (Exception ignored) {
        }

        Screen prev = previousScreen != null ? previousScreen : mc.screen;
        if (prev instanceof JoinMultiplayerScreen jms) {
            ServerData sel = resolveSelectedServer(jms);
            if (sel != null && isSteamServerId(sel.ip)) {
                return sel.ip;
            }
        }
        if (prev instanceof DirectJoinServerScreen) {
            EditBox box = findIpEditBox(prev);
            if (box != null) {
                String v = box.getValue();
                if (isSteamServerId(v)) return v;
            }
        }
        return null;
    }

    public static Screen onSetScreen(Screen next) {
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectScreen with a SteamID address:
        // bottom "Join Server" / "Connect", icon Play overlay, double-click on a list row,
        // and direct-join confirm. Button wrap alone is not enough because list entries
        // call joinSelectedServer() -> ConnectScreen.startConnecting without Button.OnPress.
        if (next instanceof ConnectScreen connectScreen) {
            // getCurrentServer() is usually null here - use list selection / direct-join field.
            // During setScreen HEAD, mc.screen is still the previous multiplayer/direct GUI.
            String addr = resolveSteamConnectAddress(mc.screen, mc);
            if (addr != null) {
                abortVanillaConnect(connectScreen);
                Screen parent = connectScreenParent(connectScreen);
                if (parent == null) parent = mc.screen;
                final Screen p = parent;
                final String steamAddr = addr;
                SteamBridgeMod.LOG.debug(
                        "Rewriting ConnectScreen -> Steam for {} (previous={})",
                        steamAddr,
                        mc.screen != null ? mc.screen.getClass().getSimpleName() : "null");
                if (!SteamManager.getInstance().isInitialized()
                        && !SteamManager.getInstance().reinit()) {
                    SteamBridgeMod.LOG.debug(
                            "Steam not running; opening launch screen before connect to {}",
                            steamAddr);
                    return new GuiSteamResync(
                            p,
                            () ->
                                    Minecraft.getInstance()
                                            .setScreen(beginSteamConnect(p, steamAddr)),
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
        return steambridge.core.SteamAddress.parse(ip) != 0L;
    }

    private static String extractSteamId(String ip) {
        String host = ip.trim();
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private static EditBox findIpEditBox(Screen gui) {
        return GuiFields.read(
                gui instanceof EditServerScreen ? GuiFields.EDITOR_IP : GuiFields.DIRECT_IP, gui);
    }

    private static EditBox findNameEditBox(Screen gui) {
        return gui instanceof EditServerScreen ? GuiFields.read(GuiFields.EDITOR_NAME, gui) : null;
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

    private static void wrapOnPress(
            Button button, java.util.function.Function<Button.OnPress, Button.OnPress> wrapper) {
        try {
            Button.OnPress original = GuiFields.BUTTON_PRESS.get(button);
            GuiFields.BUTTON_PRESS.set(button, wrapper.apply(original));
        } catch (RuntimeException failure) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "VanillaGuiIntegration-warn-1",
                    "Failed to wrap button onPress",
                    failure);
        }
    }

    private static void addButton(Screen gui, Button button) {
        Screens.getButtons(gui).add(button);
    }

    /**
     * Called from ConnectScreenMixin (remapped ServerData - not reflection).
     *
     * @return true if Steam connect started (caller cancels vanilla).
     */
    public static boolean trySteamConnectFromServerData(Screen parent, ServerData data) {
        if (data == null || data.ip == null || !isSteamServerId(data.ip)) {
            return false;
        }
        Screen p = parent != null ? parent : Minecraft.getInstance().screen;
        interceptSteamConnect(p, data.ip);
        return true;
    }

    private static void interceptSteamConnect(Screen parent, String steamAddr) {
        Minecraft mc = Minecraft.getInstance();
        if (!SteamManager.getInstance().isInitialized()) {
            if (!SteamManager.getInstance().reinit()) {
                SteamBridgeMod.LOG.debug(
                        "Steam not running; opening launch screen before connect to {}", steamAddr);
                mc.setScreen(
                        new GuiSteamResync(
                                parent,
                                () ->
                                        Minecraft.getInstance()
                                                .setScreen(beginSteamConnect(parent, steamAddr)),
                                "steambridge.gui.resync_success_hint_connect"));
                return;
            }
        }
        mc.setScreen(beginSteamConnect(parent, steamAddr));
    }

    /**
     * Tear down any leftover Steam client and open {@link GuiSteamConnecting}. Returns the
     * connecting screen so {@link #onSetScreen} can replace ConnectScreen cleanly.
     */
    private static GuiSteamConnecting beginSteamConnect(Screen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.debug("Intercepted connection to SteamID: {}", steamId);
        // Force-close previous session so reconnect / second list-join is not stuck on TCP closed.
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) {
            try {
                active.disconnect();
            } catch (Exception e) {
                ProblemLog.warn(
                        SteamBridgeMod.LOG,
                        LOG_LIMITER,
                        "VanillaGuiIntegration-warn-2",
                        "[SteamClient] disconnect before reconnect: {}",
                        e.getMessage());
            }
        }
        SteamManager.getInstance().setActiveClient(null);
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient();
        SteamClient client = new SteamClient();
        client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
        return new GuiSteamConnecting(parent, client);
    }

    /**
     * {@link ConnectScreen#startConnecting} always starts DNS/TCP after setScreen. Setting {@code
     * aborted=true} stops the "Unknown host" race when we replace the screen.
     */
    private static void abortVanillaConnect(ConnectScreen screen) {
        try {
            GuiFields.CONNECT_ABORTED.set(screen, true);
            net.minecraft.network.Connection connection =
                    GuiFields.read(GuiFields.CONNECT_CONNECTION, screen);
            if (connection != null) {
                connection.disconnect(
                        net.minecraft.network.chat.Component.literal(
                                "Steam Bridge: aborted vanilla connect"));
                GuiFields.CONNECT_CONNECTION.set(screen, null);
            }
        } catch (RuntimeException failure) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "VanillaGuiIntegration-warn-3",
                    "Could not abort ConnectScreen",
                    failure);
        }
    }

    private static Screen connectScreenParent(ConnectScreen screen) {
        Screen parent = GuiFields.read(GuiFields.CONNECT_PARENT, screen);
        return parent != null ? parent : Minecraft.getInstance().screen;
    }

    /**
     * Selected multiplayer-list entry (not {@code editingServer}, which named field lookup may hit
     * first).
     */
    private static ServerData resolveSelectedServer(JoinMultiplayerScreen jms) {
        ServerSelectionList list = GuiFields.read(GuiFields.SERVER_LIST, jms);
        Object selected = list == null ? null : list.getSelected();
        return selected instanceof ServerSelectionList.OnlineServerEntry
                ? ((ServerSelectionList.OnlineServerEntry) selected).getServerData()
                : null;
    }

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
            if (icon != null
                    && icon.length > 0
                    && !java.util.Arrays.equals(icon, data.getIconBytes())) {
                data.setIconBytes(icon);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Steam multiplayer-list polish throttle. Immediate when the SteamID set changes; otherwise at
     * most every 5s (avatars).
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
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static void markAllSteamServers(JoinMultiplayerScreen gui) {
        markAllSteamServers(gui, false);
    }

    private static void markAllSteamServers(JoinMultiplayerScreen gui, boolean force) {
        if (!SteamManager.getInstance().isInitialized()) return;
        ServerList list = gui.getServers();
        if (list == null) return;
        try {
            String fingerprint = steamListFingerprint(list);
            long now = System.currentTimeMillis();
            boolean steamSetChanged = !fingerprint.equals(lastSteamListFingerprint);
            if (!force
                    && !steamSetChanged
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
        } catch (Exception ignored) {
        }
    }

    private static void injectSteamConnectIntercept(Screen gui) {
        if (gui instanceof DirectJoinServerScreen) {
            Button join = findButtonByMessage(gui, "selectServer.select");
            if (join == null) return;
            wrapOnPress(
                    join,
                    original ->
                            b -> {
                                EditBox ip = findIpEditBox(gui);
                                String addr = ip != null ? ip.getValue() : "";
                                if (isSteamServerId(addr)) {
                                    Screen last = GuiFields.read(GuiFields.DIRECT_PARENT, gui);
                                    interceptSteamConnect(last != null ? last : gui, addr);
                                } else {
                                    original.onPress(b);
                                }
                            });
        } else if (gui instanceof JoinMultiplayerScreen jms) {
            // Early wrap for the bottom-bar "Join Server" / "Connect" button.
            // Play overlay + double-click are handled in onSetScreen via ConnectScreen.
            Button join = findButtonByMessage(gui, "selectServer.select");
            if (join == null) return;
            wrapOnPress(
                    join,
                    original ->
                            b -> {
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
        final String sid = pendingSteamId;
        final String name = pendingServerName;
        pendingSteamId = null;
        pendingServerName = null;

        if (gui instanceof EditServerScreen) {
            ServerData sd = GuiFields.read(GuiFields.EDITOR_SERVER, gui);
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
        SteamSocial.Worlds.Settings saved = null;
        if (srv != null) {
            saved = SteamSocial.Worlds.get().load(worldKey(srv));
            pendingTransportMode = SteamSocial.Worlds.parseTransportMode(saved.transportMode);
            pendingAccessPolicy = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);
        }

        EditBox portEdit = GuiFields.read(GuiFields.LAN_PORT_EDIT, gui);
        int rowY =
                portEdit != null
                        ? portEdit.getY() + portEdit.getHeight() + 20
                        : gui.height / 4 + 62;

        Button access =
                Button.builder(
                                Component.literal(accessPolicyLabel(pendingAccessPolicy)),
                                b -> {
                                    pendingAccessPolicy =
                                            (pendingAccessPolicy
                                                            == SteamServer.AccessPolicy.EVERYONE)
                                                    ? SteamServer.AccessPolicy.FRIENDS_ONLY
                                                    : SteamServer.AccessPolicy.EVERYONE;
                                    b.setMessage(
                                            Component.literal(
                                                    accessPolicyLabel(pendingAccessPolicy)));
                                    saveShareToLanSettings(gui);
                                })
                        .bounds(gui.width / 2 - 155, rowY, 150, 20)
                        .build();

        Button transport =
                Button.builder(
                                Component.literal(transportLabel(pendingTransportMode)),
                                b -> {
                                    pendingTransportMode = nextTransportMode(pendingTransportMode);
                                    b.setMessage(
                                            Component.literal(
                                                    transportLabel(pendingTransportMode)));
                                    saveShareToLanSettings(gui);
                                })
                        .bounds(gui.width / 2 + 5, rowY, 150, 20)
                        .build();

        addButton(gui, access);
        addButton(gui, transport);

        Button startLan = findButtonByMessage(gui, "lanServer.start");
        Button cancel = findButtonByMessage(gui, "gui.cancel");
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
        addButton(
                gui,
                Button.builder(
                                Component.translatable("steambridge.gui.open_steam"),
                                b -> startSteamHost(gui))
                        .bounds(gui.width / 2 - 48, bottomY, 96, 20)
                        .build());
    }

    private static void injectFriendsButton(Screen gui) {
        if (!(gui instanceof EditServerScreen || gui instanceof DirectJoinServerScreen)) return;
        EditBox ip = findIpEditBox(gui);
        if (ip == null) return;

        Button friends =
                Button.builder(
                                Component.translatable("steambridge.gui.friends_short"),
                                b -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (gui instanceof EditServerScreen) {
                                        EditBox nameBox = findNameEditBox(gui);
                                        pendingServerName =
                                                nameBox != null ? nameBox.getValue() : null;
                                    } else {
                                        pendingServerName = null;
                                    }
                                    if (SteamManager.getInstance().isInitialized()) {
                                        mc.setScreen(
                                                new GuiSteamFriends(
                                                        gui,
                                                        null,
                                                        steamId -> pendingSteamId = steamId));
                                    } else {
                                        mc.setScreen(
                                                new GuiSteamResync(
                                                        gui, steamId -> pendingSteamId = steamId));
                                    }
                                })
                        .bounds(ip.getX() + ip.getWidth() + 4, ip.getY(), 20, 20)
                        .build();
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

        addButton(
                gui,
                Button.builder(
                                Component.translatable("steambridge.gui.manage_session"),
                                b ->
                                        Minecraft.getInstance()
                                                .setScreen(new GuiSteamHostManagement(gui)))
                        .bounds(x, insertY, w, 20)
                        .build());
    }

    private static GameType readLanGameType(Screen gui, IntegratedServer srv) {
        GameType selected = GuiFields.read(GuiFields.LAN_MODE, gui);
        return selected == null ? srv.getDefaultGameType() : selected;
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
                    ProblemLog.warn(
                            SteamBridgeMod.LOG,
                            LOG_LIMITER,
                            "VanillaGuiIntegration-warn-4",
                            "[SteamHost] Failed to launch Steam: {}",
                            e.getMessage());
                }
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("steambridge.gui.host_steam_launching")
                                    .withStyle(ChatFormatting.YELLOW),
                            false);
                }
                return;
            }
        }

        GameType gameType = readLanGameType(gui, srv);
        boolean commands = Boolean.TRUE.equals(GuiFields.read(GuiFields.LAN_COMMANDS, gui));

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, pendingAccessPolicy, pendingTransportMode);

        Integer selectedPort = GuiFields.read(GuiFields.LAN_PORT, gui);
        int port =
                selectedPort != null && selectedPort > 0 && selectedPort <= 65535
                        ? selectedPort
                        : HttpUtil.getAvailablePort();
        boolean published = srv.isPublished() || srv.publishServer(gameType, commands, port);
        if (!published) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_failed")
                                .withStyle(ChatFormatting.RED),
                        false);
            }
            return;
        }
        HostAuth.applyAfterPublish(srv);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey);
        server.setTransportMode(pendingTransportMode);
        server.setMcPort(srv.getPort());
        server.start();

        if (mc.player != null) {
            if (server.isRunning()) {
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_started")
                                .withStyle(ChatFormatting.GREEN),
                        false);
            } else {
                mc.player.displayClientMessage(
                        Component.translatable("steambridge.gui.host_failed")
                                .withStyle(ChatFormatting.RED),
                        false);
            }
        }
        mc.setScreen(null);
    }

    private static void saveShareToLanSettings(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;
        SteamSocial.Worlds.get().save(worldKey(srv), pendingAccessPolicy, pendingTransportMode);
    }

    private static SteamServer.TransportMode nextTransportMode(SteamServer.TransportMode mode) {
        switch (mode) {
            case AUTO:
                return SteamServer.TransportMode.P2P_ONLY;
            case P2P_ONLY:
                return SteamServer.TransportMode.RELAY_ONLY;
            case RELAY_ONLY:
            default:
                return SteamServer.TransportMode.AUTO;
        }
    }

    private static String transportLabel(SteamServer.TransportMode mode) {
        switch (mode) {
            case P2P_ONLY:
                return I18n.get("steambridge.gui.route_p2p");
            case RELAY_ONLY:
                return I18n.get("steambridge.gui.route_relay");
            case AUTO:
            default:
                return I18n.get("steambridge.gui.route_auto");
        }
    }

    private static String accessPolicyLabel(SteamServer.AccessPolicy policy) {
        return I18n.get(
                policy == SteamServer.AccessPolicy.EVERYONE
                        ? "steambridge.gui.access_everyone"
                        : "steambridge.gui.access_friends");
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
        IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated == null) {
            return false;
        }
        try {
            String folder = worldKey(integrated);
            String hosted = server.getWorldKey();
            if (folder != null
                    && !folder.isEmpty()
                    && hosted != null
                    && !hosted.isEmpty()
                    && !hosted.equals("__default_world__")
                    && !folder.equals(hosted)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
