/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.event;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import steambridge.SteamBridgeMod;
import steambridge.gui.GuiSteamConnecting;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Client-side gameplay/GUI event handlers (Fabric 1.20.1). */
public final class SteamClientEvents {

    private static final int TRANSIENT_DISCONNECT_GRACE_TICKS = 160;
    private static final String MOD_MISMATCH_HINT_KEY = "steambridge.disconnect.mod_mismatch_hint";

    private static int deferredClientDisconnectTicks = -1;
    private static int deferredServerStopTicks = -1;

    private SteamClientEvents() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SteamClientEvents::onClientTick);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SteamClient steamClient = SteamManager.getInstance().getActiveClient();
            if (steamClient != null) {
                steamClient.onMinecraftHandshakeStarted("login");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLoggingOut());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() != server) {
                return;
            }
            ServerPlayer player = handler.getPlayer();
            onPlayerLoggedIn(player);
        });
    }

    /**
     * Called from {@code Minecraft.setScreen} mixin for non-null screens only.
     * {@code setScreen(null)} is allowed through by the mixin so the client can leave
     * {@link ReceivingLevelScreen} and enter the world.
     *
     * @return {@code null} to cancel opening, a different screen to replace, or the same screen to proceed.
     */
    public static Screen onSetScreen(Screen next) {
        if (next == null) {
            return null;
        }

        SteamClient client = SteamManager.getInstance().getActiveClient();

        if (client != null && next instanceof DisconnectedScreen) {
            if (client.getState() == SteamClient.State.CONNECTING) {
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Ignoring secondary DisconnectedScreen from vanilla background thread while Steam connection is negotiating.");
                return null;
            }

            deferredClientDisconnectTicks = -1;
            Screen result = next;
            if (isGenericDisconnectDuringLogin(client)) {
                SteamBridgeMod.LOG.warn(
                    "[SteamBridge] Replacing generic login disconnect with mod mismatch hint. state={}",
                    client.getState());
                result = createModMismatchHintScreen();
            }
            client.onMinecraftDisconnect("disconnect", "");
            client.disconnect();
            return result;
        }

        if (client != null && next instanceof ReceivingLevelScreen) {
            client.onMinecraftWorldLoading();
        }

        return next;
    }

    private static void onLoggingOut() {
        Minecraft mc = Minecraft.getInstance();

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server != null && server.isRunning()) {
            if (shouldDeferSteamServerStop(mc)) {
                deferredServerStopTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferring Steam host shutdown after client disconnect; screen={} level={} integratedServer={}",
                    screenName(mc.screen), mc.level != null, mc.getSingleplayerServer() != null);
            } else {
                deferredServerStopTicks = -1;
                server.stop();
            }
        }

        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null && client.isAlive()) {
            if (shouldShowLoginMismatchHint(mc, client)) {
                deferredClientDisconnectTicks = -1;
                showLoginMismatchHint(mc, client);
            } else if (shouldDeferSteamClientDisconnect(mc, client)) {
                deferredClientDisconnectTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferring Steam client disconnect; possible dimension transfer. screen={} level={} player={} channelOpen={}",
                    screenName(mc.screen), mc.level != null, mc.player != null, client.isSteamChannelOpen());
            } else {
                deferredClientDisconnectTicks = -1;
                client.disconnect();
            }
        }
    }

    private static void onClientTick(Minecraft mc) {
        processDeferredNetworkTeardown(mc);

        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null && client.isAlive() && mc.level != null && mc.player != null && !client.isInWorld()) {
            client.onMinecraftWorldJoined(mc.player.getName().getString(), 0);
        }

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server != null && server.isRunning()) {
            if (mc.level == null && mc.getSingleplayerServer() == null && !(mc.screen instanceof ReceivingLevelScreen)) {
                SteamBridgeMod.LOG.info("[SteamBridge] World closed, stopping Steam server...");
                server.stop();
            }
        }
    }

    private static void onPlayerLoggedIn(ServerPlayer player) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return;
        }
        if (player.connection == null) {
            return;
        }

        SocketAddress remote = player.connection.connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress) {
            server.attachMinecraftPlayer((InetSocketAddress) remote, player.getName().getString());
        }
    }

    private static void processDeferredNetworkTeardown(Minecraft mc) {
        if (deferredClientDisconnectTicks >= 0) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client == null || !client.isAlive()) {
                deferredClientDisconnectTicks = -1;
            } else if (shouldCloseDeferredClient(mc, client)) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam client disconnect resolved as real disconnect. screen={} channelOpen={}",
                    screenName(mc.screen), client.isSteamChannelOpen());
                client.disconnect();
            } else if (mc.level != null && mc.player != null && client.isSteamChannelOpen()) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info("[SteamBridge] Preserved Steam client across transient world reload.");
            } else if (--deferredClientDisconnectTicks <= 0) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Transient disconnect grace window expired; tearing down Steam client. screen={} channelOpen={}",
                    screenName(mc.screen), client.isSteamChannelOpen());
                client.disconnect();
            }
        }

        if (deferredServerStopTicks >= 0) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server == null || !server.isRunning()) {
                deferredServerStopTicks = -1;
            } else if (shouldCloseDeferredServer(mc)) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam host shutdown resolved as real disconnect. screen={}",
                    screenName(mc.screen));
                server.stop();
            } else if (mc.getSingleplayerServer() != null && mc.level != null) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info("[SteamBridge] Preserved Steam host across transient world reload.");
            } else if (--deferredServerStopTicks <= 0) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Transient host grace window expired; stopping Steam server. screen={} integratedServer={}",
                    screenName(mc.screen), mc.getSingleplayerServer() != null);
                server.stop();
            }
        }
    }

    private static boolean shouldDeferSteamClientDisconnect(Minecraft mc, SteamClient client) {
        return client != null
            && client.isAlive()
            && client.isSteamChannelOpen()
            && isLikelyTransientSteamDisconnectScreen(mc.screen);
    }

    private static boolean shouldDeferSteamServerStop(Minecraft mc) {
        return mc.getSingleplayerServer() != null
            && isLikelyTransientSteamDisconnectScreen(mc.screen);
    }

    private static boolean shouldShowLoginMismatchHint(Minecraft mc, SteamClient client) {
        if (client == null || mc.level != null || mc.player != null) {
            return false;
        }

        SteamClient.State state = client.getState();
        if (state != SteamClient.State.STEAM_READY && state != SteamClient.State.NEGOTIATING) {
            return false;
        }

        Screen screen = mc.screen;
        return screen == null
            || screen instanceof GuiSteamConnecting
            || screen instanceof DisconnectedScreen;
    }

    private static void showLoginMismatchHint(Minecraft mc, SteamClient client) {
        SteamBridgeMod.LOG.warn(
            "[SteamBridge] Minecraft connection closed during Steam login before a detailed disconnect screen appeared. screen={} state={}",
            screenName(mc.screen), client.getState());
        client.closeAfterMinecraftFailure("connect.failed", modMismatchHintText());
        mc.execute(() -> {
            Screen current = mc.screen;
            if (current == null || current instanceof GuiSteamConnecting || current instanceof DisconnectedScreen) {
                mc.setScreen(createModMismatchHintScreen());
            }
        });
    }

    private static boolean shouldCloseDeferredClient(Minecraft mc, SteamClient client) {
        if (client == null || !client.isSteamChannelOpen()) {
            return true;
        }
        return isFinalDisconnectScreen(mc.screen);
    }

    private static boolean shouldCloseDeferredServer(Minecraft mc) {
        return mc.getSingleplayerServer() == null || isFinalDisconnectScreen(mc.screen);
    }

    private static boolean isLikelyTransientSteamDisconnectScreen(Screen screen) {
        return screen == null
            || screen instanceof ReceivingLevelScreen
            || isGalacticraftTravelScreen(screen);
    }

    private static boolean isGalacticraftTravelScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("galacticraft")
            || name.contains("asmodeuscore")
            || name.contains("celestial")
            || name.contains("planet");
    }

    private static boolean isFinalDisconnectScreen(Screen screen) {
        return screen instanceof DisconnectedScreen
            || screen instanceof JoinMultiplayerScreen
            || screen instanceof TitleScreen;
    }

    private static boolean isGenericDisconnectDuringLogin(SteamClient client) {
        if (client == null) {
            return false;
        }
        SteamClient.State state = client.getState();
        return state == SteamClient.State.STEAM_READY || state == SteamClient.State.NEGOTIATING;
    }

    private static DisconnectedScreen createModMismatchHintScreen() {
        return new DisconnectedScreen(
            new JoinMultiplayerScreen(new TitleScreen()),
            Component.translatable("connect.failed"),
            Component.translatable(MOD_MISMATCH_HINT_KEY));
    }

    private static String modMismatchHintText() {
        try {
            if (net.minecraft.client.resources.language.I18n.exists(MOD_MISMATCH_HINT_KEY)) {
                return net.minecraft.client.resources.language.I18n.get(MOD_MISMATCH_HINT_KEY);
            }
        } catch (Exception ignored) {}
        return "Connection closed during the login handshake. Your mods probably do not match the host's mods.";
    }

    private static String screenName(Screen screen) {
        return screen != null ? screen.getClass().getName() : "<none>";
    }
}
