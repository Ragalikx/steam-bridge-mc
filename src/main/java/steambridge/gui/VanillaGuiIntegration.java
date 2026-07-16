/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import steambridge.SteamAppIdHelper;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import steambridge.steam.SteamSocial;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.client.util.NetworkUtils;
import net.minecraft.text.Text;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameMode;
import net.minecraft.util.WorldSavePath;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Injects Steam Bridge controls into vanilla multiplayer/LAN screens. */
public final class VanillaGuiIntegration {

    private VanillaGuiIntegration() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen instanceof MultiplayerScreen) {
                markAllSteamServers((MultiplayerScreen) client.currentScreen);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            onScreenInit(screen);
            if (screen instanceof OpenToLanScreen) {
                ScreenEvents.afterRender(screen).register((s, matrices, mouseX, mouseY, delta) ->
                        renderShareToLanSteamTitle(s, matrices));
            }
        });
    }

    public static Screen onSetScreen(Screen next) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (next instanceof ConnectScreen) {
            ConnectScreen connectScreen = (ConnectScreen) next;
            ServerInfo sd = mc.getCurrentServerEntry();
            if (sd != null && isSteamServerId(sd.address)) {
                abortVanillaConnect(connectScreen);
                Screen parent = connectScreenParent(connectScreen);
                if (parent == null) parent = mc.currentScreen;
                return beginSteamConnect(parent, sd.address);
            }
        }
        if (next instanceof OpenToLanScreen) {
            if (isSteamHostSessionActive(mc)) {
                return new GuiSteamHostManagement(mc.currentScreen);
            }
            if (mc.getServer() != null) {
                try {
                    String worldKey = worldKey(mc.getServer());
                    SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey);
                    setByType(next, GameMode.class, SteamSocial.Worlds.parseGameType(saved.gametype));
                    setByType(next, boolean.class, Boolean.valueOf(saved.allowCommands));
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to load OpenToLan defaults", e);
                }
            }
        }
        return next;
    }

    private static void renderShareToLanSteamTitle(Screen gui, MatrixStack matrix) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String title = I18n.translate("steambridge.gui.steam_settings");
        tr.draw(matrix, title, gui.width / 2 - tr.getWidth(title) / 2, 128, 0xFFFFFF);
    }

    private static void onScreenInit(Screen gui) {
        applyPendingSteamId(gui);
        injectShareToLanControls(gui);
        injectFriendsButton(gui);
        injectSteamConnectIntercept(gui);
        if (gui instanceof MultiplayerScreen) {
            markAllSteamServers((MultiplayerScreen) gui);
        }
        injectPauseMenuControl(gui);
    }

    /** Transport route the host will start with, cycled by the ButtonWidget on the Share-to-LAN screen. */
    static SteamServer.TransportMode pendingTransportMode = SteamServer.TransportMode.AUTO;
    /** Access policy (Friends/Everyone), toggled by the ButtonWidget on the Share-to-LAN screen. */
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
     * TextFieldWidget; on {@link DirectConnectScreen} (direct connect) there is only one.
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

    // -- Button helpers --------------------------------------------------------

    private static void addButton(Screen gui, ButtonWidget button) {
        Screens.getButtons(gui).add(button);
    }

    // -- Vanilla-ButtonWidget lookup by message --------------------------------------

    private static ButtonWidget findButtonByMessage(Screen gui, String translationKey) {
        String want = I18n.translate(translationKey);
        for (ClickableWidget w : Screens.getButtons(gui)) {
            if (w instanceof ButtonWidget) {
                ButtonWidget b = (ButtonWidget) w;
                if (b.getMessage().getString().equals(want)) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * Wraps a live {@link ButtonWidget}'s {@code onPress} callback in place (found via type-based
     * reflection). Lets us intercept a vanilla ButtonWidget without touching position/active state.
     */
    private static void wrapOnPress(ButtonWidget button, java.util.function.Function<ButtonWidget.PressAction, ButtonWidget.PressAction> wrapper) {
        for (Field f : ButtonWidget.class.getDeclaredFields()) {
            if (ButtonWidget.PressAction.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    ButtonWidget.PressAction original = (ButtonWidget.PressAction) f.get(button);
                    f.set(button, wrapper.apply(original));
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("Failed to wrap ButtonWidget onPress", e);
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
        MinecraftClient.getInstance().openScreen(beginSteamConnect(parent, steamAddr));
    }

    /**
     * {@link ConnectScreen} starts a DNS/TCP thread even if the screen is replaced.
     * Cancel the flag and tear down any ClientConnection it already created so it cannot
     * race with our Steam loopback ClientConnection.
     */
    private static void abortVanillaConnect(ConnectScreen screen) {
        boolean set = false;
        for (Field f : ConnectScreen.class.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                    f.setBoolean(screen, true);
                    set = true;
                } else if (ClientConnection.class.isAssignableFrom(f.getType())) {
                    Object v = f.get(screen);
                    if (v instanceof ClientConnection) {
                        ClientConnection cc = (ClientConnection) v;
                        if (cc.isOpen()) {
                            cc.disconnect(new LiteralText("Steam Bridge: aborted vanilla connect"));
                        }
                        f.set(screen, null);
                        SteamBridgeMod.LOG.info("[SteamBridge] Aborted ConnectScreen ClientConnection");
                    }
                }
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
                if (p instanceof Screen) return (Screen) p;
            } catch (Exception ignored) {}
        }
        return MinecraftClient.getInstance().currentScreen;
    }

    // -- Steam-server marking (ping suppression + null MOTD safety) ------------

    private static void sanitizeServerInfoFields(ServerInfo data) {
        if (data.label == null) {
            data.label = LiteralText.EMPTY;
        }
        if (data.playerCountLabel == null) {
            data.playerCountLabel = LiteralText.EMPTY;
        }
        if (data.version == null) {
            data.version = new LiteralText("???");
        }
        if (data.playerListSummary == null) {
            data.playerListSummary = java.util.Collections.emptyList();
        }
    }

    /** Skip TCP ping for a SteamID entry and give it safe display components + avatar icon. */
    private static void markSteamServer(ServerInfo data) {
        data.online = true;
        data.ping = 0L;
        data.label = new TranslatableText("steambridge.gui.server_steam_motd");
        data.playerCountLabel = new TranslatableText("steambridge.gui.server_steam_status");
        if (data.version == null) {
            data.version = new LiteralText("Steam");
        }
        if (data.playerListSummary == null) {
            data.playerListSummary = java.util.Collections.emptyList();
        }

        try {
            long steamId = Long.parseLong(extractSteamId(data.address));
            String iconB64 = SteamSocial.ProfileCache.get().getAvatarIconB64(steamId);
            if (iconB64 != null && !iconB64.isEmpty()
                    && !iconB64.equals(data.getIcon())) {
                data.setIcon(iconB64);
            }
        } catch (Exception ignored) {
            // keep default icon until Steam has the avatar ready
        }
    }

    private static void markAllSteamServers(MultiplayerScreen gui) {
        ServerList list = gui.getServerList();
        if (list == null) return;
        try {
            int steamIndex = 0;
            for (int i = 0; i < list.size(); i++) {
                ServerInfo data = list.get(i);
                if (data == null) continue;
                sanitizeServerInfoFields(data);
                if (isSteamServerId(data.address)) {
                    markSteamServer(data);
                    if (i > steamIndex) list.swapEntries(i, steamIndex);
                    steamIndex++;
                }
            }
        } catch (Exception ignored) {}
    }

    private static void injectSteamConnectIntercept(Screen gui) {
        if (gui instanceof DirectConnectScreen) {
            ButtonWidget join = findButtonByMessage(gui, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                TextFieldWidget ip = findIpEditBox(gui);
                String addr = ip != null ? ip.getText() : "";
                if (isSteamServerId(addr)) {
                    Screen last = findByType(gui, Screen.class);
                    interceptSteamConnect(last != null ? last : gui, addr);
                } else {
                    original.onPress(b);
                }
            });
        } else if (gui instanceof MultiplayerScreen) {
            final MultiplayerScreen mps = (MultiplayerScreen) gui;
            ButtonWidget join = findButtonByMessage(gui, "selectServer.select");
            if (join == null) return;
            wrapOnPress(join, original -> b -> {
                ServerInfo selected = findByType(gui, ServerInfo.class);
                if (selected != null && isSteamServerId(selected.address)) {
                    interceptSteamConnect(mps, selected.address);
                } else {
                    original.onPress(b);
                }
            });
        }
    }

    // -- Injection helpers -----------------------------------------------------

    private static void applyPendingSteamId(Screen gui) {
        if (pendingSteamId == null) return;
        final String sid  = pendingSteamId;
        final String name = pendingServerName;
        pendingSteamId    = null;
        pendingServerName = null;

        if (gui instanceof AddServerScreen) {
            ServerInfo sd = findByType(gui, ServerInfo.class);
            if (sd != null) sd.address = sid;
            TextFieldWidget ip = findIpEditBox(gui);
            if (ip != null) ip.setText(sid);
            if (name != null) {
                TextFieldWidget nameBox = findNameEditBox(gui);
                if (nameBox != null) nameBox.setText(name);
            }
        } else {
            TextFieldWidget tf = findIpEditBox(gui);
            if (tf != null) tf.setText(sid);
        }
    }

    private static void injectShareToLanControls(Screen gui) {
        if (!(gui instanceof OpenToLanScreen)) return;

        IntegratedServer srv = MinecraftClient.getInstance().getServer();
        if (srv != null) {
            SteamSocial.Worlds.Settings saved = SteamSocial.Worlds.get().load(worldKey(srv));
            pendingTransportMode = SteamSocial.Worlds.parseTransportMode(saved.transportMode);
            pendingAccessPolicy  = SteamSocial.Worlds.parseAccessPolicy(saved.accessPolicy);

            // ShareToLan init overwrites commands from level.dat. If saved value differs, press once.
            if (saved.allowCommands != findPrimitiveBoolean(gui)) {
                String commandsLabel = I18n.translate("selectWorld.allowCommands");
                for (ClickableWidget w : Screens.getButtons(gui)) {
                    if (w instanceof ButtonWidget) {
                        ButtonWidget btn = (ButtonWidget) w;
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
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int steamOptsY = 140;

        Text accessMsg = new LiteralText(accessPolicyLabel(pendingAccessPolicy));
        Text routeMsg  = new LiteralText(transportLabel(pendingTransportMode));

        addButton(gui, new ButtonWidget(gui.width / 2 - 155, steamOptsY, 150, GuiButtons.HEIGHT,
                accessMsg, b -> {
            pendingAccessPolicy = (pendingAccessPolicy == SteamServer.AccessPolicy.EVERYONE)
                    ? SteamServer.AccessPolicy.FRIENDS_ONLY : SteamServer.AccessPolicy.EVERYONE;
            b.setMessage(new LiteralText(accessPolicyLabel(pendingAccessPolicy)));
            saveShareToLanSettings(gui);
        }));

        addButton(gui, new ButtonWidget(gui.width / 2 + 5, steamOptsY, 150, GuiButtons.HEIGHT,
                routeMsg, b -> {
            pendingTransportMode = nextTransportMode(pendingTransportMode);
            b.setMessage(new LiteralText(transportLabel(pendingTransportMode)));
            saveShareToLanSettings(gui);
        }));

        ButtonWidget startLan = findButtonByMessage(gui, "lanServer.start");
        ButtonWidget cancel   = findButtonByMessage(gui, "gui.cancel");
        int bottomY = gui.height - 28;
        int gap = 6;
        int leftEdge = gui.width / 2 - 155;
        int rightEdge = gui.width / 2 + 155;

        Text openSteamMsg = new TranslatableText("steambridge.gui.open_steam");
        Text startMsg = startLan != null ? startLan.getMessage() : new TranslatableText("lanServer.start");
        Text cancelMsg = cancel != null ? cancel.getMessage() : new TranslatableText("gui.cancel");

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
        addButton(gui, new ButtonWidget(x0 + startW + gap, bottomY, steamW, GuiButtons.HEIGHT,
                openSteamMsg, b -> startSteamHost(gui)));
    }
    private static void injectFriendsButton(Screen gui) {
        if (!(gui instanceof AddServerScreen || gui instanceof DirectConnectScreen)) return;
        TextFieldWidget ip = findIpEditBox(gui);
        if (ip == null) return;

        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        Text friendsMsg = new TranslatableText("steambridge.gui.friends_short");
        addButton(gui, GuiButtons.create(MinecraftClient.getInstance().textRenderer, ip.x + ip.getWidth() + 4, ip.y, friendsMsg, b -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (gui instanceof AddServerScreen) {
                TextFieldWidget nameBox = findNameEditBox(gui);
                pendingServerName = nameBox != null ? nameBox.getText() : null;
            } else {
                pendingServerName = null;
            }
            if (SteamManager.getInstance().isInitialized()) {
                mc.openScreen(new GuiSteamFriends(gui, null, steamId -> pendingSteamId = steamId));
            } else {
                mc.openScreen(new GuiSteamResync(gui, steamId -> pendingSteamId = steamId));
            }
        }, 20, 80));
    }

    /**
     * After the world is open on Steam, vanilla disables {@code menu.shareToLan}
     * ({@code isPublished() == true}). Forge 1.16.5 also has no "Mods" row on this screen
     * (unlike 1.19+), so the 1.19.2 "insert above Mods" trick finds nothing.
     * <p>
     * Match 1.12.2: repurpose the gray "Open to LAN" ButtonWidget into "Manage Steam session".
     * If that label is missing (modded pause menus), fall back to inserting a new ButtonWidget
     * above Return to Menu / Disconnect.
     */
    private static void injectPauseMenuControl(Screen gui) {
        if (!(gui instanceof GameMenuScreen)) return;
        if (!isSteamHostSessionActive(MinecraftClient.getInstance())) return;

        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        Text manageMsg = new TranslatableText("steambridge.gui.manage_session");

        ButtonWidget shareToLan = findButtonByMessage(gui, "menu.shareToLan");
        if (shareToLan != null) {
            // Keep the vanilla 98px dual-column slot - do not grow past neighbors.
            shareToLan.active = true;
            shareToLan.setMessage(manageMsg);
            wrapOnPress(shareToLan, original -> b ->
                    MinecraftClient.getInstance().openScreen(new GuiSteamHostManagement(gui)));
            return;
        }

        // Fallback for heavily modded pause menus without shareToLan.
        ButtonWidget anchor = findButtonByMessage(gui, "menu.returnToMenu");
        if (anchor == null) {
            anchor = findButtonByMessage(gui, "menu.disconnect");
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
        for (ClickableWidget w : Screens.getButtons(gui)) {
            if (w instanceof ButtonWidget) {
                ButtonWidget b = (ButtonWidget) w;
                if (b.y >= y) {
                    b.y += rowShift;
                }
            }
        }
        addButton(gui, new ButtonWidget(x, y, manageW, GuiButtons.HEIGHT, manageMsg,
                b -> MinecraftClient.getInstance().openScreen(new GuiSteamHostManagement(gui))));
    }

    // -- Host start ------------------------------------------------------------

    private static void startSteamHost(Screen gui) {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer srv = mc.getServer();
        if (srv == null) return;

        if (!SteamManager.getInstance().isInitialized()) {
            if (!SteamManager.getInstance().reinit()) {
                try {
                    SteamAppIdHelper.ensureAppId(mc.runDirectory);
                    SteamAppIdHelper.launchSteam();
                } catch (Exception e) {
                    SteamBridgeMod.LOG.warn("[SteamHost] Failed to launch Steam: {}", e.getMessage());
                }
                if (mc.player != null) {
                    mc.player.sendMessage(
                            new LiteralText("\u00a7e" + I18n.translate("steambridge.gui.host_steam_launching")), false);
                }
                return;
            }
        }

        GameMode gameType = findByType(gui, GameMode.class);
        if (gameType == null) gameType = GameMode.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, gameType, commands, pendingAccessPolicy, pendingTransportMode);

        int port = NetworkUtils.findLocalPort();
        boolean published = srv.openToLan(gameType, commands, port);
        // Steam friends often join offline/cracked; keep LAN offline even if the
        // integrated server started with online-mode=true (1.16 openToLan does not flip it).
        if (published) {
            srv.setOnlineMode(false);
        }

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey, "World");
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getServerPort());
        server.start();

        if (mc.player != null) {
            if (server.isRunning()) {
                mc.player.sendMessage(
                        new LiteralText("\u00a7a" + I18n.translate("steambridge.gui.host_started")), false);
            } else {
                mc.player.sendMessage(
                        new LiteralText("\u00a7c" + I18n.translate("steambridge.gui.host_failed")), false);
            }
        }
        mc.openScreen(null);
    }

    private static void saveShareToLanSettings(Screen gui) {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer srv = mc.getServer();
        if (srv == null) return;
        GameMode gameType = findByType(gui, GameMode.class);
        if (gameType == null) gameType = GameMode.SURVIVAL;
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
            case P2P_ONLY:   return I18n.translate("steambridge.gui.route_p2p");
            case RELAY_ONLY: return I18n.translate("steambridge.gui.route_relay");
            case AUTO:
            default:         return I18n.translate("steambridge.gui.route_auto");
        }
    }

    private static String accessPolicyLabel(SteamServer.AccessPolicy policy) {
        return I18n.translate(policy == SteamServer.AccessPolicy.EVERYONE
                ? "steambridge.gui.access_everyone" : "steambridge.gui.access_friends");
    }

    /** World folder name - used as the stable per-world settings/ban key. */
    private static String worldKey(IntegratedServer srv) {
        try {
            return srv.getSavePath(WorldSavePath.ROOT).getParent().getFileName().toString();
        } catch (Exception e) {
            return "__default_world__";
        }
    }

    private static boolean isSteamHostSessionActive(MinecraftClient mc) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return false;
        }
        IntegratedServer integrated = mc.getServer();
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
