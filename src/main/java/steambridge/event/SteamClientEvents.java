/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.event;

import steambridge.SteamBridgeMod;
import steambridge.gui.GuiSteamConnecting;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Client-side gameplay/GUI event handlers (NeoForge 1.21.1).
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} by {@link SteamBridgeMod}. Handles teardown of the
 * Steam client/host on disconnect (with a short grace window across transient world
 * reloads / dimension transfers), late binding of Minecraft identities to Steam peers on
 * the host, and a mod-mismatch hint when a modded login handshake fails.</p>
 */
public class SteamClientEvents {

    private static final int TRANSIENT_DISCONNECT_GRACE_TICKS = 160;
    private static final String MOD_MISMATCH_HINT_KEY = "steambridge.disconnect.mod_mismatch_hint";

    private int deferredClientDisconnectTicks = -1;
    private int deferredServerStopTicks = -1;

    // -- Screen open ----------------------------------------------------------

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        SteamClient client = SteamManager.getInstance().getActiveClient();

        if (client != null && next instanceof DisconnectedScreen) {
            if (client.getState() == SteamClient.State.CONNECTING) {
                SteamBridgeMod.LOG.info("[SteamBridge] Ignoring secondary DisconnectedScreen from vanilla background thread while Steam connection is negotiating.");
                event.setCanceled(true);
                return;
            }

            deferredClientDisconnectTicks = -1;
            if (isGenericDisconnectDuringLogin(client)) {
                SteamBridgeMod.LOG.warn(
                    "[SteamBridge] Replacing generic login disconnect with mod mismatch hint. state={}",
                    client.getState());
                event.setNewScreen(createModMismatchHintScreen());
            }
            client.onMinecraftDisconnect("disconnect", "");
            client.disconnect();
        }

        if (client != null && next instanceof ReceivingLevelScreen) {
            client.onMinecraftWorldLoading();
        }
    }

    // -- Network in/out -------------------------------------------------------

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null) {
            client.onMinecraftHandshakeStarted("login");
        }
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
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

    // -- Tick -----------------------------------------------------------------

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {

        Minecraft mc = Minecraft.getInstance();
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

    // -- Host: bind Minecraft identity to Steam peer --------------------------

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return;
        }
        if (player.connection == null) {
            return;
        }

        // In MC 1.21.1 the `connection` field on ServerCommonPacketListenerImpl is protected.
        // Access it via reflection to get the loopback port for Steam player matching.
        try {
            java.lang.reflect.Field connField = null;
            Class<?> cls = player.connection.getClass();
            while (cls != null && connField == null) {
                try { connField = cls.getDeclaredField("connection"); }
                catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            }
            if (connField != null) {
                connField.setAccessible(true);
                net.minecraft.network.Connection conn =
                        (net.minecraft.network.Connection) connField.get(player.connection);
                if (conn != null) {
                    SocketAddress remote = conn.getRemoteAddress();
                    if (remote instanceof InetSocketAddress) {
                        server.attachMinecraftPlayer(
                                (InetSocketAddress) remote, player.getName().getString());
                    }
                }
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[SteamBridge] Could not read remote address for {}: {}",
                    player.getName().getString(), e.getMessage());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && event.getEntity().getUUID().equals(mc.player.getUUID())) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client != null) {
                client.onMinecraftWorldJoined(event.getEntity().getName().getString(), 0);
            }
        }
    }

    // -- Deferred teardown ----------------------------------------------------

    private void processDeferredNetworkTeardown(Minecraft mc) {
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
                deferredClientDisconnectTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Still waiting on transient Steam client disconnect. screen={} channelOpen={}",
                    screenName(mc.screen), client.isSteamChannelOpen());
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
                deferredServerStopTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Still waiting on transient Steam host disconnect. screen={} integratedServer={}",
                    screenName(mc.screen), mc.getSingleplayerServer() != null);
            }
        }
    }

    private boolean shouldDeferSteamClientDisconnect(Minecraft mc, SteamClient client) {
        return client != null
            && client.isAlive()
            && client.isSteamChannelOpen()
            && isLikelyTransientSteamDisconnectScreen(mc.screen);
    }

    private boolean shouldDeferSteamServerStop(Minecraft mc) {
        return mc.getSingleplayerServer() != null
            && isLikelyTransientSteamDisconnectScreen(mc.screen);
    }

    private boolean shouldShowLoginMismatchHint(Minecraft mc, SteamClient client) {
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

    private void showLoginMismatchHint(Minecraft mc, SteamClient client) {
        SteamBridgeMod.LOG.warn(
            "[SteamBridge] Minecraft connection closed during Steam/Forge login before a detailed disconnect screen appeared. screen={} state={}",
            screenName(mc.screen), client.getState());
        client.closeAfterMinecraftFailure("connect.failed", modMismatchHintText());
        mc.execute(() -> {
            Screen current = mc.screen;
            if (current == null || current instanceof GuiSteamConnecting || current instanceof DisconnectedScreen) {
                mc.setScreen(createModMismatchHintScreen());
            }
        });
    }

    private boolean shouldCloseDeferredClient(Minecraft mc, SteamClient client) {
        if (client == null || !client.isSteamChannelOpen()) {
            return true;
        }
        return isFinalDisconnectScreen(mc.screen);
    }

    private boolean shouldCloseDeferredServer(Minecraft mc) {
        return mc.getSingleplayerServer() == null || isFinalDisconnectScreen(mc.screen);
    }

    private boolean isLikelyTransientSteamDisconnectScreen(Screen screen) {
        return screen == null
            || screen instanceof ReceivingLevelScreen
            || isGalacticraftTravelScreen(screen);
    }

    private boolean isGalacticraftTravelScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("galacticraft")
            || name.contains("asmodeuscore")
            || name.contains("celestial")
            || name.contains("planet");
    }

    private boolean isFinalDisconnectScreen(Screen screen) {
        return screen instanceof DisconnectedScreen
            || screen instanceof JoinMultiplayerScreen
            || screen instanceof TitleScreen;
    }

    private boolean isGenericDisconnectDuringLogin(SteamClient client) {
        if (client == null) {
            return false;
        }
        SteamClient.State state = client.getState();
        return state == SteamClient.State.STEAM_READY || state == SteamClient.State.NEGOTIATING;
    }

    private DisconnectedScreen createModMismatchHintScreen() {
        return new DisconnectedScreen(
            new JoinMultiplayerScreen(new TitleScreen()),
            Component.translatable("connect.failed"),
            Component.translatable(MOD_MISMATCH_HINT_KEY));
    }

    private String modMismatchHintText() {
        try {
            if (net.minecraft.client.resources.language.I18n.exists(MOD_MISMATCH_HINT_KEY)) {
                return net.minecraft.client.resources.language.I18n.get(MOD_MISMATCH_HINT_KEY);
            }
        } catch (Exception ignored) {}
        return "Connection closed during the NeoForge login handshake. Your mods probably do not match the host's mods.";
    }

    private String screenName(Screen screen) {
        return screen != null ? screen.getClass().getName() : "<none>";
    }
}
