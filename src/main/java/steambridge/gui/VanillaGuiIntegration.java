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

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
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

    /** Opens {@link GuiSteamConnecting} for a SteamID-shaped address, replacing normal vanilla connect. */
    private static void interceptSteamConnect(Screen parent, String steamAddr) {
        long steamId = Long.parseLong(extractSteamId(steamAddr));
        SteamBridgeMod.LOG.info("Intercepted connection to SteamID: {}", steamId);
        SteamClient active = SteamManager.getInstance().getActiveClient();
        if (active != null) active.disconnect();
        SteamClient client = new SteamClient();
        client.connect(com.codedisaster.steamworks.SteamID.createFromNativeHandle(steamId), parent);
        Minecraft.getInstance().setScreen(new GuiSteamConnecting(parent, client));
    }

    // -- Steam-server marking (ping suppression) -------------------------------

    private static void markAllSteamServers(JoinMultiplayerScreen gui) {
        ServerList list = gui.getServers();
        if (list == null) return;
        try {
            int steamIndex = 0;
            for (int i = 0; i < list.size(); i++) {
                ServerData data = list.get(i);
                if (isSteamServerId(data.ip)) {
                    data.pinged = true;
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

        if (next instanceof ShareToLanScreen) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server != null && server.isRunning()) {
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
        if (gui instanceof JoinMultiplayerScreen jms) markAllSteamServers(jms);
        injectPauseMenuControl(event, gui);
    }

    /**
     * Intercepts the vanilla "Join Server" button on {@link DirectJoinServerScreen} (typed
     * address) and {@link JoinMultiplayerScreen} (selected list entry) so a SteamID-shaped
     * address opens {@link GuiSteamConnecting} instead of vanilla's normal TCP connect attempt.
     * <p>
     * {@code ConnectScreen} exposes no public accessor for the address it is about to dial, so
     * interception has to happen one step earlier, at the button that triggers it,
     * rather than in {@code onScreenOpening} for {@code ConnectScreen} itself.
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

            // ShareToLanScreen.init() overwrites commands from level.dat, discarding
            // what onScreenOpening set. If our saved value differs, press the CycleButton once.
            if (saved.allowCommands != findPrimitiveBoolean(gui)) {
                // 1.19.2 uses selectWorld.allowCommands (no ".new" suffix).
                String commandsLabel = I18n.get("selectWorld.allowCommands");
                for (GuiEventListener l : event.getListenersList()) {
                    if (l instanceof CycleButton<?> btn
                            && btn.getMessage().getString().contains(commandsLabel)) {
                        btn.onPress();
                        break;
                    }
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
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) return;

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
                        Component.literal("§e" + I18n.get("steambridge.gui.host_steam_launching")), false);
                return;
            }
            // reinit succeeded, fall through and open the world
        }

        GameType gameType = findByType(gui, GameType.class);
        if (gameType == null) gameType = GameType.SURVIVAL;
        boolean commands = findPrimitiveBoolean(gui);

        String worldKey = worldKey(srv);
        SteamSocial.Worlds.get().save(worldKey, gameType, commands, pendingAccessPolicy, pendingTransportMode);

        int port = HttpUtil.getAvailablePort();
        boolean published = srv.publishServer(gameType, commands, port);

        SteamServer server = new SteamServer(pendingAccessPolicy, worldKey, "World");
        server.setTransportMode(pendingTransportMode);
        if (published) server.setMcPort(srv.getPort());
        server.start();

        if (server.isRunning()) {
            mc.player.displayClientMessage(
                    Component.literal("§a" + I18n.get("steambridge.gui.host_started")), false);
        } else {
            mc.player.displayClientMessage(
                    Component.literal("§c" + I18n.get("steambridge.gui.host_failed")), false);
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
            return srv.getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
        } catch (Exception e) {
            return "__default_world__";
        }
    }
}
