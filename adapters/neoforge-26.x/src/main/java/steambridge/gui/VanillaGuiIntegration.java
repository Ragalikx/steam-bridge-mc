/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.ManageServerScreen;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;
import steambridge.steam.HostAuth;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;

import java.lang.reflect.Field;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
@EventBusSubscriber(modid = SteamBridgeMod.MODID, value = Dist.CLIENT)
public final class VanillaGuiIntegration {
    public static void acceptSteamInvite(long host) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        SteamManager manager = SteamManager.getInstance();
        Object generation = manager.inviteGeneration();
        mc.execute(
                () -> {
                    if (!manager.isInviteGeneration(generation)) return;
                    if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)
                            && !(mc.screen
                                    instanceof
                                    net.minecraft.client.gui.screens.multiplayer
                                            .JoinMultiplayerScreen)) {
                        steambridge.core.ProblemLog.warn(
                                SteamBridgeMod.LOG,
                                LOG_LIMITER,
                                "invite-menu",
                                "Return to the main or multiplayer menu before accepting a Steam"
                                    + " invitation");
                        return;
                    }
                    if (mc.level != null
                            || (manager.getActiveClient() != null
                                    && manager.getActiveClient().isAlive())
                            || (manager.getActiveServer() != null
                                    && manager.getActiveServer().isRunning())
                            || mc.screen instanceof GuiSteamConnecting) {
                        steambridge.core.ProblemLog.warn(
                                SteamBridgeMod.LOG,
                                LOG_LIMITER,
                                "invite-busy",
                                "Steam invitation ignored while a game or connection is active");
                        return;
                    }
                    mc.setScreen(beginSteamConnect(mc.screen, Long.toString(host)));
                });
    }

    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private VanillaGuiIntegration() {}

    /**
     * Transport route the host will start with, cycled by the button on the Share-to-LAN screen.
     */
    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;

    /** Access policy (Friends/Everyone), toggled by the button on the Share-to-LAN screen. */
    static SteamServer.AccessPolicy pendingAccessPolicy = SteamServer.AccessPolicy.FRIENDS_ONLY;

    /** Pending SteamID to inject when the parent screen re-initializes. */
    static String pendingSteamId = null;

    /** Pending server name to restore in ManageServerScreen after picking a friend. */
    static String pendingServerName = null;

    // -- Steam-ID helpers ------------------------------------------------------

    private static boolean isSteamServerId(String ip) {
        return steambridge.core.SteamAddress.parse(ip) != 0L;
    }

    private static String extractSteamId(String ip) {
        String host = ip.trim();
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private static Field guiField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static <T> T readGuiField(Object owner, String name, Class<T> type) {
        try {
            return type.cast(guiField(owner.getClass(), name).get(owner));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "VanillaGuiIntegration-warn-1",
                    "Cannot read GUI field {}.{}",
                    owner.getClass().getName(),
                    name,
                    failure);
            return null;
        }
    }

    private static EditBox findIpEditBox(Screen gui) {
        return readGuiField(gui, "ipEdit", EditBox.class);
    }

    private static EditBox findNameEditBox(Screen gui) {
        return gui instanceof ManageServerScreen
                ? readGuiField(gui, "nameEdit", EditBox.class)
                : null;
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

    private static void wrapOnPress(
            Button button, java.util.function.Function<Button.OnPress, Button.OnPress> wrapper) {
        try {
            Field field = guiField(Button.class, "onPress");
            Button.OnPress original = (Button.OnPress) field.get(button);
            field.set(button, wrapper.apply(original));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "VanillaGuiIntegration-warn-2",
                    "Failed to wrap button onPress",
                    failure);
        }
    }

    /**
     * Join a SteamID-shaped address. If Steam is not up, show {@link GuiSteamResync} ("Launching
     * Steam...") instead of failing later with a confusing relay error.
     */
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
            // Steam may have been started after the game; try once before showing the wait UI.
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
     * connecting screen so {@link #onScreenOpening} can replace ConnectScreen cleanly.
     */
    private static GuiSteamConnecting beginSteamConnect(Screen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.debug("Intercepted connection to SteamID: {}", steamId);
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) {
            try {
                active.disconnect();
            } catch (Exception e) {
                ProblemLog.warn(
                        SteamBridgeMod.LOG,
                        LOG_LIMITER,
                        "VanillaGuiIntegration-warn-3",
                        "[SteamClient] disconnect before reconnect: {}",
                        e.getMessage());
            }
        }
        SteamManager.getInstance().setActiveClient(null);
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient();
        SteamClient client = new SteamClient();
        client.connect(steambridge.panama.SteamID.createFromNativeHandle(steamId), parent);
        return new GuiSteamConnecting(parent, client);
    }

    private static void abortVanillaConnect(ConnectScreen screen) {
        try {
            guiField(ConnectScreen.class, "aborted").setBoolean(screen, true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProblemLog.warn(
                    SteamBridgeMod.LOG,
                    LOG_LIMITER,
                    "VanillaGuiIntegration-warn-4",
                    "Could not abort ConnectScreen",
                    failure);
        }
    }

    private static Screen connectScreenParent(ConnectScreen screen) {
        Screen parent = readGuiField(screen, "parent", Screen.class);
        return parent != null ? parent : Minecraft.getInstance().screen;
    }

    private static ServerData resolveSelectedServer(JoinMultiplayerScreen gui) {
        ServerSelectionList list =
                readGuiField(gui, "serverSelectionList", ServerSelectionList.class);
        return list != null
                        && list.getSelected() instanceof ServerSelectionList.OnlineServerEntry entry
                ? entry.getServerData()
                : null;
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

    // -- Events ----------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof JoinMultiplayerScreen jms) {
            markAllSteamServers(jms);
        }
    }

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

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectScreen with a SteamID address:
        // bottom Join/Connect, icon Play overlay, double-click, direct-join confirm.
        if (next instanceof ConnectScreen connectScreen) {
            // getCurrentServer() is usually null here - use list selection / direct-join field.
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
                    event.setNewScreen(
                            new GuiSteamResync(
                                    p,
                                    () ->
                                            Minecraft.getInstance()
                                                    .setScreen(beginSteamConnect(p, steamAddr)),
                                    "steambridge.gui.resync_success_hint_connect"));
                } else {
                    event.setNewScreen(beginSteamConnect(p, steamAddr));
                }
                return;
            }
        }

        if (next instanceof ShareToLanScreen) {
            if (isSteamHostSessionActive(mc)) {
                event.setNewScreen(new GuiSteamHostManagement(mc.screen));
                return;
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
     * Early wrap for bottom-bar Join/Connect. Play overlay + double-click go through {@link
     * JoinMultiplayerScreen#joinSelectedServer} and are caught in {@link #onScreenOpening} when
     * {@link ConnectScreen} opens (with abort of the leftover vanilla connector thread).
     */
    private static void injectSteamConnectIntercept(ScreenEvent.Init.Post event, Screen gui) {
        if (gui instanceof DirectJoinServerScreen) {
            Button join = findButtonByMessage(event, "selectServer.select");
            if (join == null) return;
            wrapOnPress(
                    join,
                    original ->
                            b -> {
                                EditBox ip = findIpEditBox(gui);
                                String addr = ip != null ? ip.getValue() : "";
                                if (isSteamServerId(addr)) {
                                    Screen last = readGuiField(gui, "lastScreen", Screen.class);
                                    interceptSteamConnect(last != null ? last : gui, addr);
                                } else {
                                    original.onPress(b);
                                }
                            });
        } else if (gui instanceof JoinMultiplayerScreen jms) {
            Button join = findButtonByMessage(event, "selectServer.select");
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

    // -- Injection helpers -----------------------------------------------------

    private static void applyPendingSteamId(ScreenEvent.Init event, Screen gui) {
        if (pendingSteamId == null) return;
        final String sid = pendingSteamId;
        final String name = pendingServerName;
        pendingSteamId = null;
        pendingServerName = null;

        if (gui instanceof ManageServerScreen) {
            ServerData sd = readGuiField(gui, "serverData", ServerData.class);
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
            pendingAccessPolicy = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);
        }

        EditBox portEdit = readGuiField(gui, "portEdit", EditBox.class);
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

        event.addListener(access);
        event.addListener(transport);

        Button startLan = findButtonByMessage(event, "lanServer.start");
        // 26.2+ replaced "Start LAN World" with "Apply Changes" in the same footer slot.
        if (startLan == null) startLan = findButtonByMessage(event, "menu.multiplayerOptions.applyChanges");
        Button cancel = findButtonByMessage(event, "gui.cancel");
        Button steam =
                Button.builder(
                                Component.translatable("steambridge.gui.open_steam"),
                                b -> startSteamHost(gui))
                        .bounds(gui.width / 2 - 48, gui.height - 28, 96, 20)
                        .build();
        event.addListener(steam);

        lanScreen = gui;
        lanApply = startLan;
        lanCancel = cancel;
        lanSteam = steam;
        lanAccess = access;
        lanTransport = transport;
        lanLayout = findFooterLayout(gui);
        if (lanLayout != null) {
            // Init.Post runs again on every resize while the layout object survives: grow from the
            // footer height first seen, never cumulatively.
            Integer base = LAN_BASE_FOOTER.computeIfAbsent(lanLayout, l -> l.getFooterHeight());
            lanLayout.setFooterHeight(base + LAN_ROW);
            repositionElements(gui);
        }
        layoutShareToLan(gui);
    }

    private static final int LAN_ROW = 24;
    private static final java.util.Map<HeaderAndFooterLayout, Integer> LAN_BASE_FOOTER =
            new java.util.WeakHashMap<>();
    private static Screen lanScreen;
    private static HeaderAndFooterLayout lanLayout;
    private static Button lanApply, lanCancel, lanSteam, lanAccess, lanTransport;

    /**
     * 26.2+ builds this screen from a HeaderAndFooterLayout that re-arranges after Init.Post and
     * whenever LAN is toggled, undoing absolute positions; 26.3 also scrolls its content down to
     * the footer. The footer is therefore one row taller: Access/Route on top, then Apply, Open via
     * Steam and Cancel. Positions are re-applied before every frame.
     */
    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() == lanScreen) layoutShareToLan(lanScreen);
    }

    /** 26.2+ screens keep a HeaderAndFooterLayout; 26.1 has none, which is expected. */
    private static HeaderAndFooterLayout findFooterLayout(Screen gui) {
        for (Class<?> type = gui.getClass(); type != null && type != Screen.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != HeaderAndFooterLayout.class) continue;
                try {
                    field.setAccessible(true);
                    return (HeaderAndFooterLayout) field.get(gui);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void repositionElements(Screen gui) {
        try {
            java.lang.reflect.Method method = Screen.class.getDeclaredMethod("repositionElements");
            method.setAccessible(true);
            method.invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            SteamBridgeMod.LOG.debug("[SteamBridge] Could not re-arrange the LAN screen: {}", failure.toString());
        }
    }

    private static void layoutShareToLan(Screen gui) {
        if (lanLayout != null) {
            int top = gui.height - lanLayout.getFooterHeight() + 4;
            lanAccess.setPosition(gui.width / 2 - 155, top);
            lanTransport.setPosition(gui.width / 2 + 5, top);
            placeLanFooter(gui, top + LAN_ROW);
            return;
        }
        Button footer = lanApply != null ? lanApply : lanCancel;
        int bottomY = footer != null ? footer.getY() : gui.height - 28;
        placeLanFooter(gui, bottomY);

        int contentBottom = gui.height / 4 + 42;
        for (GuiEventListener child : gui.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) continue;
            if (widget == lanApply || widget == lanCancel || widget == lanSteam
                    || widget == lanAccess || widget == lanTransport) continue;
            int bottom = widget.getY() + widget.getHeight();
            if (bottom < bottomY - 4) contentBottom = Math.max(contentBottom, bottom);
        }
        int rowY = contentBottom + 8;
        lanAccess.setPosition(gui.width / 2 - 155, rowY);
        lanTransport.setPosition(gui.width / 2 + 5, rowY);
    }

    private static void placeLanFooter(Screen gui, int bottomY) {
        if (lanApply != null) {
            lanApply.setWidth(96);
            lanApply.setPosition(gui.width / 2 - 152, bottomY);
        }
        if (lanCancel != null) {
            lanCancel.setWidth(96);
            lanCancel.setPosition(gui.width / 2 + 56, bottomY);
        }
        lanSteam.setPosition(gui.width / 2 - 48, bottomY);
    }

    private static void injectFriendsButton(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof ManageServerScreen || gui instanceof DirectJoinServerScreen)) return;
        EditBox ip = findIpEditBox(gui);
        if (ip == null) return;

        Button friends =
                Button.builder(
                                Component.translatable("steambridge.gui.friends_short"),
                                b -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (gui instanceof ManageServerScreen) {
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

        event.addListener(
                Button.builder(
                                Component.translatable("steambridge.gui.manage_session"),
                                b ->
                                        Minecraft.getInstance()
                                                .setScreen(new GuiSteamHostManagement(gui)))
                        .bounds(x, y, w, 20)
                        .build());
    }

    // -- Host start ------------------------------------------------------------

    private static Object lanSetting(Screen gui, String name) {
        return readGuiField(gui, name, Object.class);
    }

    private static GameType readLanGameType(Screen gui, IntegratedServer server) {
        Object value = lanSetting(gui, "gameMode");
        return value instanceof GameType mode ? mode : server.getDefaultGameType();
    }

    private static boolean publishSteamLan(
            IntegratedServer srv, GameType gameType, boolean commands, int port) {
        return srv.publishServer(gameType, commands, port);
    }

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
                    ProblemLog.warn(
                            SteamBridgeMod.LOG,
                            LOG_LIMITER,
                            "VanillaGuiIntegration-warn-5",
                            "[SteamHost] Failed to launch Steam: {}",
                            e.getMessage());
                }
                mc.player.sendSystemMessage(
                        Component.translatable("steambridge.gui.host_steam_launching")
                                .withStyle(ChatFormatting.YELLOW));
                return;
            }
            // reinit succeeded: fall through and open the world
        }

        GameType gameType = readLanGameType(gui, srv);
        boolean commands = Boolean.TRUE.equals(lanSetting(gui, "commands"));

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, pendingAccessPolicy, pendingTransportMode);

        Object configuredPort = lanSetting(gui, "port");
        int port =
                configuredPort instanceof Integer value && value > 0 && value <= 65535
                        ? value
                        : HttpUtil.getAvailablePort();
        boolean published = srv.isPublished() || publishSteamLan(srv, gameType, commands, port);
        if (!published) {
            mc.player.sendSystemMessage(
                    Component.translatable("steambridge.gui.host_failed")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        HostAuth.applyAfterPublish(srv);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey);
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (server.isRunning()) {
            mc.player.sendSystemMessage(
                    Component.translatable("steambridge.gui.host_started")
                            .withStyle(ChatFormatting.GREEN));
            mc.setScreen(null);
        } else {
            mc.player.sendSystemMessage(
                    Component.translatable("steambridge.gui.host_failed")
                            .withStyle(ChatFormatting.RED));
            mc.setScreen(null);
        }
    }

    private static void saveShareToLanSettings(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;
        GameType gameType = readLanGameType(gui, srv);
        boolean commands = Boolean.TRUE.equals(lanSetting(gui, "commands"));
        SteamSocial.Worlds.get().save(worldKey(srv), pendingAccessPolicy, pendingTransportMode);
    }

    // -- Labels ----------------------------------------------------------------

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
        net.minecraft.client.server.IntegratedServer integrated = mc.getSingleplayerServer();
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
