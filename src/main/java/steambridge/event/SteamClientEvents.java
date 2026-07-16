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

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.WorldSavePath;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;

/** Client-side gameplay/GUI event handlers (Fabric 1.16.5). */
public final class SteamClientEvents {

    private static final int TRANSIENT_DISCONNECT_GRACE_TICKS = 160;
    private static final String MOD_MISMATCH_HINT_KEY = "steambridge.disconnect.mod_mismatch_hint";

    private static int deferredClientDisconnectTicks = -1;
    private static int deferredServerStopTicks = -1;

    private SteamClientEvents() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Keep ticking the Steam login ClientConnection even if GuiSteamConnecting
            // was replaced mid-handshake (mirrors ConnectScreen ownership).
            SteamClient active = SteamManager.getInstance().getActiveClient();
            if (active != null) {
                active.tickPendingConnection();
            }
            onClientTick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SteamClient steamClient = SteamManager.getInstance().getActiveClient();
            if (steamClient != null) {
                steamClient.onMinecraftHandshakeStarted("login");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLoggingOut());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getServer() != server) {
                return;
            }
            onPlayerLoggedIn(handler.player);
        });
    }

    /**
     * Called from MinecraftClient.openScreen mixin.
     * @return null to cancel, another screen to replace, or the same screen to proceed.
     */
    public static Screen onSetScreen(Screen next) {
        if (next == null) {
            return null;
        }

        SteamClient client = SteamManager.getInstance().getActiveClient();

        if (client != null && next instanceof DisconnectedScreen) {
            DisconnectedScreen disconnected = (DisconnectedScreen) next;
            String reasonText = disconnectedReasonText(disconnected);
            if (shouldIgnoreVanillaConnectFailure(client, reasonText)) {
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Ignoring vanilla ConnectScreen failure while Steam is active. state={} reason={}",
                    client.getState(),
                    SteamBridgeMod.safeLog(reasonText));
                return null;
            }

            deferredClientDisconnectTicks = -1;
            Screen result = next;
            if (isGenericDisconnectDuringLogin(client) && looksLikeModRejection(reasonText)) {
                SteamBridgeMod.LOG.warn(
                    "[SteamBridge] Replacing login disconnect with mod mismatch hint. state={} reason={}",
                    client.getState(),
                    SteamBridgeMod.safeLog(reasonText));
                result = createModMismatchHintScreen();
            }
            client.onMinecraftDisconnect("disconnect", reasonText);
            client.disconnect();
            return result;
        }

        if (client != null && next instanceof DownloadingTerrainScreen) {
            client.onMinecraftWorldLoading();
        }
        return next;
    }

    private static void onLoggingOut() {
        MinecraftClient mc = MinecraftClient.getInstance();

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server != null && server.isRunning()) {
            if (shouldDeferSteamServerStop(mc)) {
                deferredServerStopTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
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
            } else {
                deferredClientDisconnectTicks = -1;
                client.disconnect();
            }
        }
    }

    private static void onClientTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        processDeferredNetworkTeardown(mc);

        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null && client.isAlive() && mc.world != null && mc.player != null && !client.isInWorld()) {
            client.onMinecraftWorldJoined(mc.player.getName().getString(), 0);
        }

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server != null && server.isRunning()) {
            if (mc.world == null && mc.getServer() == null && !(mc.currentScreen instanceof DownloadingTerrainScreen)) {
                SteamBridgeMod.LOG.info("[SteamBridge] World closed, stopping Steam server...");
                deferredServerStopTicks = -1;
                server.stop();
            } else if (shouldStopHostForWorldChange(mc, server)) {
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Integrated world changed (was '{}'), stopping leftover Steam host.",
                    server.getWorldKey());
                deferredServerStopTicks = -1;
                server.stop();
            }
        }
    }

    private static void onPlayerLoggedIn(ServerPlayerEntity player) {
        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning() || player.networkHandler == null) {
            return;
        }
        SocketAddress remote = player.networkHandler.connection.getAddress();
        if (remote instanceof InetSocketAddress) {
            server.attachMinecraftPlayer((InetSocketAddress) remote, player.getName().getString());
        }
    }

    private static void processDeferredNetworkTeardown(MinecraftClient mc) {
        if (deferredClientDisconnectTicks >= 0) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client == null || !client.isAlive()) {
                deferredClientDisconnectTicks = -1;
            } else if (shouldCloseDeferredClient(mc, client)) {
                deferredClientDisconnectTicks = -1;
                client.disconnect();
            } else if (mc.world != null && mc.player != null && client.isSteamChannelOpen()) {
                deferredClientDisconnectTicks = -1;
            } else if (--deferredClientDisconnectTicks <= 0) {
                deferredClientDisconnectTicks = -1;
                client.disconnect();
            }
        }

        if (deferredServerStopTicks >= 0) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server == null || !server.isRunning()) {
                deferredServerStopTicks = -1;
            } else if (shouldStopHostForWorldChange(mc, server)) {
                deferredServerStopTicks = -1;
                server.stop();
            } else if (shouldCloseDeferredServer(mc)) {
                deferredServerStopTicks = -1;
                server.stop();
            } else if (mc.getServer() != null && mc.world != null
                    && isSameHostedWorld(mc, server)) {
                deferredServerStopTicks = -1;
            } else if (--deferredServerStopTicks <= 0) {
                deferredServerStopTicks = -1;
                server.stop();
            }
        }
    }

    private static boolean shouldStopHostForWorldChange(MinecraftClient mc, SteamServer server) {
        if (server == null || !server.isRunning()) {
            return false;
        }
        IntegratedServer integrated = mc.getServer();
        if (integrated == null) {
            return false;
        }
        try {
            String folder = worldFolderName(integrated);
            if (folder == null || folder.isEmpty()) {
                return false;
            }
            String hosted = server.getWorldKey();
            return hosted != null && !hosted.isEmpty()
                    && !hosted.equals("__default_world__")
                    && !folder.equals(hosted);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSameHostedWorld(MinecraftClient mc, SteamServer server) {
        if (server == null || mc.getServer() == null) {
            return false;
        }
        try {
            String folder = worldFolderName(mc.getServer());
            String hosted = server.getWorldKey();
            return folder != null && hosted != null && folder.equals(hosted);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String worldFolderName(IntegratedServer srv) {
        try {
            return srv.getSavePath(WorldSavePath.ROOT).getParent().getFileName().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shouldDeferSteamClientDisconnect(MinecraftClient mc, SteamClient client) {
        return client != null && client.isAlive() && client.isSteamChannelOpen()
            && isLikelyTransient(mc.currentScreen);
    }

    private static boolean shouldDeferSteamServerStop(MinecraftClient mc) {
        return mc.getServer() != null && isLikelyTransient(mc.currentScreen);
    }

    private static boolean shouldShowLoginMismatchHint(MinecraftClient mc, SteamClient client) {
        if (client == null || mc.world != null || mc.player != null) return false;
        SteamClient.State state = client.getState();
        if (state != SteamClient.State.STEAM_READY && state != SteamClient.State.NEGOTIATING) return false;
        Screen screen = mc.currentScreen;
        return screen == null || screen instanceof GuiSteamConnecting || screen instanceof DisconnectedScreen;
    }

    private static void showLoginMismatchHint(MinecraftClient mc, SteamClient client) {
        client.closeAfterMinecraftFailure("connect.failed", modMismatchHintText());
        mc.execute(() -> {
            Screen current = mc.currentScreen;
            if (current == null || current instanceof GuiSteamConnecting || current instanceof DisconnectedScreen) {
                mc.openScreen(createModMismatchHintScreen());
            }
        });
    }

    private static boolean shouldCloseDeferredClient(MinecraftClient mc, SteamClient client) {
        if (client == null || !client.isSteamChannelOpen()) return true;
        return isFinalDisconnectScreen(mc.currentScreen);
    }

    private static boolean shouldCloseDeferredServer(MinecraftClient mc) {
        return mc.getServer() == null || isFinalDisconnectScreen(mc.currentScreen);
    }

    private static boolean isLikelyTransient(Screen screen) {
        return screen == null || screen instanceof DownloadingTerrainScreen;
    }

    private static boolean isFinalDisconnectScreen(Screen screen) {
        return screen instanceof DisconnectedScreen
            || screen instanceof MultiplayerScreen
            || screen instanceof TitleScreen;
    }

    private static boolean isGenericDisconnectDuringLogin(SteamClient client) {
        if (client == null) return false;
        SteamClient.State state = client.getState();
        return state == SteamClient.State.STEAM_READY || state == SteamClient.State.NEGOTIATING;
    }

    private static boolean shouldIgnoreVanillaConnectFailure(SteamClient client, String reasonText) {
        if (client == null || !client.isAlive()) return false;
        SteamClient.State state = client.getState();
        if (state != SteamClient.State.CONNECTING
                && state != SteamClient.State.STEAM_READY
                && state != SteamClient.State.NEGOTIATING) {
            return false;
        }
        if (state == SteamClient.State.CONNECTING) return true;
        String reason = reasonText != null ? reasonText.toLowerCase(Locale.ROOT) : "";
        if (reason.isEmpty()) return client.isSteamChannelOpen();
        return isNetworkishDisconnectReason(reason);
    }

    private static boolean isNetworkishDisconnectReason(String reasonLower) {
        return reasonLower.contains("unknown host")
            || reasonLower.contains("connection refused")
            || reasonLower.contains("failed to connect")
            || reasonLower.contains("timed out")
            || reasonLower.contains("timeout")
            || reasonLower.contains("ioexception")
            || reasonLower.contains("connection reset");
    }

    private static boolean looksLikeModRejection(String reasonText) {
        String reason = reasonText != null ? reasonText.toLowerCase(Locale.ROOT) : "";
        if (reason.isEmpty() || isNetworkishDisconnectReason(reason)) return false;
        return reason.contains("mod") || reason.contains("fabric")
            || reason.contains("incompatible") || reason.contains("mismatch") || reason.contains("rejected");
    }

    private static String disconnectedReasonText(DisconnectedScreen screen) {
        try {
            for (Field f : DisconnectedScreen.class.getDeclaredFields()) {
                if (!Text.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(screen);
                if (v instanceof Text) {
                    String s = ((Text) v).getString();
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {}
        try {
            Text title = screen.getTitle();
            if (title != null) return title.getString();
        } catch (Exception ignored) {}
        return "";
    }

    private static DisconnectedScreen createModMismatchHintScreen() {
        return new DisconnectedScreen(
            new MultiplayerScreen(new TitleScreen()),
            new TranslatableText("connect.failed"),
            new TranslatableText(MOD_MISMATCH_HINT_KEY));
    }

    private static String modMismatchHintText() {
        try {
            if (net.minecraft.client.resource.language.I18n.hasTranslation(MOD_MISMATCH_HINT_KEY)) {
                return net.minecraft.client.resource.language.I18n.translate(MOD_MISMATCH_HINT_KEY);
            }
        } catch (Exception ignored) {}
        return "Connection closed during the login handshake. Your mods probably do not match the host's mods.";
    }
}
