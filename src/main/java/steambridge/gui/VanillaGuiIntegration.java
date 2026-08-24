/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.HostAuth;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
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

    /**
     * The server-address field. On {@link EditServerScreen} it is the first declared EditBox
     * (ipEdit precedes nameEdit); on {@link DirectJoinServerScreen} there is only one.
     */
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
     * Wraps a live {@link Button}'s {@code onPress} callback in place (found via type-based
     * reflection ({@code Button} has exactly one field of type {@code Button.OnPress}). This
     * lets us intercept a vanilla button's action without touching its position/active/visible
     * state, which vanilla continues to manage normally (e.g. "Join Server" being disabled
     * until a server-list entry is selected).
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
     * Starts a Steam P2P connect and returns the status screen. Does not call
     * {@link Minecraft#setScreen} so callers can either set it themselves or inject it via
     * {@link ScreenEvent.Opening#setNewScreen}.
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

    /** Opens {@link GuiSteamConnecting} for a SteamID-shaped address, replacing normal vanilla connect. */
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
     * {@link ConnectScreen#startConnecting} always calls {@code connect()} after
     * {@code setScreen}, even if Opening replaces the screen. Setting {@code aborted}
     * stops the DNS/TCP thread from racing in a "Unknown host" disconnect.
     * <p>
     * Field is found by type (not name) so this works on production SRG runtime where
     * the field is not called {@code aborted}.
     */
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

    // -- Steam-server marking (ping suppression + null MOTD safety) ------------

    /**
     * Vanilla {@code OnlineServerEntry.render} does {@code font.split(serverData.motd, ...)}.
     * MOTD/status are only filled when a TCP ping starts. We set {@code pinged=true} so Steam
     * IDs are never pinged, but then MOTD stays null (especially after load from servers.dat)
     * and the multiplayer list crashes every launch until the entry is deleted.
     */
    private static void sanitizeServerDataFields(ServerData data) {
        if (data.motd == null) {
            data.motd = CommonComponents.EMPTY;
        }
        if (data.status == null) {
            data.status = CommonComponents.EMPTY;
        }
        if (data.version == null) {
            data.version = Component.literal("???");
        }
        if (data.playerList == null) {
            data.playerList = java.util.Collections.emptyList();
        }
    }

    /** Skip TCP ping for a SteamID entry and give it safe display Components + avatar icon. */
    private static void markSteamServer(ServerData data) {
        data.pinged = true;
        // Not -2 (vanilla "still pinging" spinner); 0 looks like a quiet live entry.
        data.ping = 0L;
        data.motd = Component.translatable("steambridge.gui.server_steam_motd");
        data.status = Component.translatable("steambridge.gui.server_steam_status");
        if (data.version == null) {
            data.version = Component.literal("Steam");
        }
        if (data.playerList == null) {
            data.playerList = java.util.Collections.emptyList();
        }

        // Prefer the friend's Steam avatar over the default unknown-server tile.
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
                // Belt-and-suspenders for any corrupt list entry (null MOTD NPE).
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
        if (mc.screen instanceof JoinMultiplayerScreen jms) {
            markAllSteamServers(jms);
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();

        // Catch every path that opens ConnectScreen with a SteamID address:
        // bottom "Join Server" button (if not already wrapped), icon Play overlay,
        // double-click on a list row, and direct-join confirm. Button wrap alone is
        // not enough because OnlineServerEntry.mouseClicked calls joinSelectedServer()
        // directly without going through Button.OnPress.
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
     * Early intercept on the bottom-bar Join / Direct-join buttons. The icon "Play" overlay
     * and double-click go through {@link JoinMultiplayerScreen#joinSelectedServer} and are
     * caught in {@link #onScreenOpening} when {@link ConnectScreen} opens (with abort of the
     * leftover vanilla connector thread).
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
                ServerData selected = findByType(gui, ServerData.class);
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

                }
            }
        }

        // Layout (same idea as 1.12.2 / NeoForge-1.20.1):
        //   y~100 vanilla game mode + commands
        //   section title "Steam session settings" (drawn in onShareToLanRender)
        //   access + route row under that title
        //   bottom row: [Start LAN] [Open via Steam] [Cancel]
        Font font = Minecraft.getInstance().font;
        // Game mode sits at y=100 on 1.19.2 ShareToLan; steam opts under the section title.
        int steamOptsY = 140;

        Component accessMsg = Component.literal(accessPolicyLabel(pendingAccessPolicy));
        Component routeMsg  = Component.literal(transportLabel(pendingTransportMode));

        // Same dual-column 150 slots as vanilla game-mode / commands (and as 1.20.1).
        event.addListener(new Button(gui.width / 2 - 155, steamOptsY, 150, GuiButtons.HEIGHT,
                accessMsg, b -> {
            pendingAccessPolicy = (pendingAccessPolicy == SteamServer.AccessPolicy.EVERYONE)
                    ? SteamServer.AccessPolicy.FRIENDS_ONLY : SteamServer.AccessPolicy.EVERYONE;
            b.setMessage(Component.literal(accessPolicyLabel(pendingAccessPolicy)));
            saveShareToLanSettings(gui);
        }));

        event.addListener(new Button(gui.width / 2 + 5, steamOptsY, 150, GuiButtons.HEIGHT,
                routeMsg, b -> {
            pendingTransportMode = nextTransportMode(pendingTransportMode);
            b.setMessage(Component.literal(transportLabel(pendingTransportMode)));
            saveShareToLanSettings(gui);
        }));

        // Bottom: Start LAN | Open via Steam | Cancel (Open Steam between the two vanilla ones).
        Button startLan = findButtonByMessage(event, "lanServer.start");
        Button cancel   = findButtonByMessage(event, "gui.cancel");
        int bottomY = gui.height - 28;
        int gap = 6;
        int leftEdge = gui.width / 2 - 155;
        int rightEdge = gui.width / 2 + 155;

        Component openSteamMsg = Component.translatable("steambridge.gui.open_steam");
        Component startMsg = startLan != null ? startLan.getMessage() : Component.translatable("lanServer.start");
        Component cancelMsg = cancel != null ? cancel.getMessage() : Component.translatable("gui.cancel");

        int slotMax = 110;
        int startW = GuiButtons.fitWidth(font, startMsg, 80, slotMax);
        int steamW = GuiButtons.fitWidth(font, openSteamMsg, 80, slotMax);
        int cancelW = GuiButtons.fitWidth(font, cancelMsg, 80, slotMax);
        int total = startW + steamW + cancelW + 2 * gap;
        int span = rightEdge - leftEdge;
        if (total > span) {
            // Prefer equal shrink so nothing drops off the dual-column frame.
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
        event.addListener(new Button(x0 + startW + gap, bottomY, steamW, GuiButtons.HEIGHT,
                openSteamMsg, b -> startSteamHost(gui)));
    }

    /**
     * Draws the "Steam session settings" heading under vanilla's "Other players" block
     * and above the access/route toggles (same role as 1.12.2 DrawScreenEvent hook).
     */
    @SubscribeEvent
    public static void onShareToLanRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ShareToLanScreen gui)) return;
        // steam opts row is at y=140; title sits just above it.
        int titleY = 128;
        String title = I18n.get("steambridge.gui.steam_settings");
        PoseStack pose = event.getPoseStack();
        Font font = Minecraft.getInstance().font;
        int x = gui.width / 2 - font.width(title) / 2;
        font.draw(pose, title, x, titleY, 0xFFFFFF);
    }

    private static void injectFriendsButton(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof EditServerScreen || gui instanceof DirectJoinServerScreen)) return;
        EditBox ip = findIpEditBox(gui);
        if (ip == null) return;

        var font = Minecraft.getInstance().font;
        Component friendsMsg = Component.translatable("steambridge.gui.friends_short");
        // Sit just right of the IP field; size to label (min 20 for the "S" glyph).
        event.addListener(GuiButtons.create(font, ip.x + ip.getWidth() + 4, ip.y, friendsMsg, b -> {
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
        }, 20, 80));
    }

    private static void injectPauseMenuControl(ScreenEvent.Init.Post event, Screen gui) {
        if (!(gui instanceof PauseScreen)) return;
        if (!isSteamHostSessionActive(Minecraft.getInstance())) return;

// Anchor above Forge "Mods". Size to the Steam label; if wider than Mods, grow
        // rightward from Mods.x so short English and long Russian both fit.
        Button mods = findButtonByMessage(event, "fml.menu.mods");
        if (mods == null) return;

        var font = Minecraft.getInstance().font;
        Component manageMsg = Component.translatable("steambridge.gui.manage_session");
        int manageW = GuiButtons.fitWidth(font, manageMsg, mods.getWidth(), gui.width - mods.x - 8);
        int x = mods.x, y = mods.y;
        final int rowShift = 24;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.y >= y) {
                b.y += rowShift;
            }
        }

        event.addListener(new Button(x, y, manageW, GuiButtons.HEIGHT, manageMsg,
                b -> Minecraft.getInstance().setScreen(new GuiSteamHostManagement(gui))));
    }

    // -- Host start ------------------------------------------------------------

    private static void startSteamHost(Screen gui) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null) return;

        if (!SteamManager.getInstance().isInitialized()) {
            // Try reinit first in case Steam was launched recently
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
            // reinit succeeded, fall through and open the world
        }

        GameType gameType = findByType(gui, GameType.class);
        if (gameType == null) gameType = GameType.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, pendingAccessPolicy, pendingTransportMode);

        int port = HttpUtil.getAvailablePort();
        boolean published = srv.publishServer(gameType, commands, port);
        if (published) HostAuth.applyAfterPublish(srv);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey, "World");
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (server.isRunning()) {
            mc.player.displayClientMessage(
                    Component.translatable("steambridge.gui.host_started").withStyle(ChatFormatting.GREEN), false);
        } else {
            mc.player.displayClientMessage(
                    Component.translatable("steambridge.gui.host_failed").withStyle(ChatFormatting.RED), false);
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
        SteamSocial.Worlds.get().save(worldKey(srv), pendingAccessPolicy, pendingTransportMode);
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
