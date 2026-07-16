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
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;

/**
 * Client-side gameplay/GUI event handlers, ported from the 1.12.2 {@code ClientProxy}.
 *
 * <p>Registered on the Forge event bus by {@link SteamBridgeMod}. Handles teardown of the
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

        if (client != null && next instanceof DisconnectedScreen disconnected) {
            // Server-list joins open ConnectScreen first; even after we swap to GuiSteamConnecting,
            // startConnecting still starts a DNS/TCP thread. That thread often ends as
            // "Unknown host" while Steam is already STEAM_READY/NEGOTIATING - and used to kill
            // the real session and show a false mod-mismatch hint. Direct-connect never hits
            // ConnectScreen, which is why only the server list path broke.
            String reasonText = disconnectedReasonText(disconnected);
            if (shouldIgnoreVanillaConnectFailure(client, reasonText)) {
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Ignoring vanilla ConnectScreen failure while Steam is active. state={} reason={}",
                    client.getState(),
                    SteamBridgeMod.safeLog(reasonText));
                event.setCanceled(true);
                return;
            }

            deferredClientDisconnectTicks = -1;
            if (isGenericDisconnectDuringLogin(client) && looksLikeModRejection(reasonText)) {
                SteamBridgeMod.LOG.warn(
                    "[SteamBridge] Replacing login disconnect with mod mismatch hint. state={} reason={}",
                    client.getState(),
                    SteamBridgeMod.safeLog(reasonText));
                event.setNewScreen(createModMismatchHintScreen());
            }
            client.onMinecraftDisconnect("disconnect", reasonText);
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
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

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

        SocketAddress remote = player.connection.connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress) {
            server.attachMinecraftPlayer((InetSocketAddress) remote, player.getName().getString());
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

    // -- Screen classification helpers (reflection-free) ----------------------

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

    /**
     * True when DisconnectedScreen is almost certainly the leftover ConnectScreen DNS/TCP
     * failure (or similar), not a real Forge mod-rejection from the integrated server.
     */
    private boolean shouldIgnoreVanillaConnectFailure(SteamClient client, String reasonText) {
        if (client == null || !client.isAlive()) {
            return false;
        }
        SteamClient.State state = client.getState();
        // Still bringing Steam up, or MC handshake already in progress over Steam.
        if (state != SteamClient.State.CONNECTING
                && state != SteamClient.State.STEAM_READY
                && state != SteamClient.State.NEGOTIATING) {
            return false;
        }
        // While Steam is still connecting, any parallel vanilla disconnect is noise.
        if (state == SteamClient.State.CONNECTING) {
            return true;
        }
        // After Steam is ready, only ignore clear network/DNS failures - keep real login rejects.
        String reason = reasonText != null ? reasonText.toLowerCase(Locale.ROOT) : "";
        if (reason.isEmpty()) {
            // Unknown-host screen often has a translated body; empty still happens.
            // If the Steam channel is open, prefer not to tear it down for an empty reason.
            return client.isSteamChannelOpen();
        }
        return isNetworkishDisconnectReason(reason);
    }

    private static boolean isNetworkishDisconnectReason(String reasonLower) {
        return reasonLower.contains("unknown host")
            || reasonLower.contains("неизв")
            || reasonLower.contains("cannot_resolve")
            || reasonLower.contains("cannot resolve")
            || reasonLower.contains("connection refused")
            || reasonLower.contains("failed to connect")
            || reasonLower.contains("couldn't connect")
            || reasonLower.contains("could not connect")
            || reasonLower.contains("timed out")
            || reasonLower.contains("timeout")
            || reasonLower.contains("disconnect.genericreason")
            || reasonLower.contains("server.invalid")
            || reasonLower.contains("ioexception")
            || reasonLower.contains("connection reset");
    }

    private static boolean looksLikeModRejection(String reasonText) {
        String reason = reasonText != null ? reasonText.toLowerCase(Locale.ROOT) : "";
        if (reason.isEmpty() || isNetworkishDisconnectReason(reason)) {
            return false;
        }
        return reason.contains("mod")
            || reason.contains("fml")
            || reason.contains("forge")
            || reason.contains("incompatible")
            || reason.contains("mismatch")
            || reason.contains("rejected");
    }

    private static String disconnectedReasonText(DisconnectedScreen screen) {
        try {
            for (Field f : DisconnectedScreen.class.getDeclaredFields()) {
                if (!Component.class.isAssignableFrom(f.getType())) continue;
                // title is also Component (super); reason is the detailed one - prefer longer text
                f.setAccessible(true);
                Object v = f.get(screen);
                if (v instanceof Component c) {
                    String s = c.getString();
                    if (s != null && !s.isEmpty()) {
                        // Prefer non-title looking long messages; still return first useful
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {}
        // Title is connect.failed often - try getTitle
        try {
            Component title = screen.getTitle();
            if (title != null) {
                return title.getString();
            }
        } catch (Exception ignored) {}
        return "";
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
        return "Connection closed during the Forge login handshake. Your mods probably do not match the host's mods.";
    }

    private String screenName(Screen screen) {
        return screen != null ? screen.getClass().getName() : "<none>";
    }
}
