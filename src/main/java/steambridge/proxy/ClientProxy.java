/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;
import steambridge.gui.GuiSteamConnecting;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class ClientProxy extends CommonProxy {

    private static final int TRANSIENT_DISCONNECT_GRACE_TICKS = 160;
    private static final String MOD_MISMATCH_HINT_KEY = "steambridge.disconnect.mod_mismatch_hint";
    private static final String MOD_MISMATCH_HINT_FALLBACK =
        "Connection closed during the Forge login handshake. Your mods probably do not match the host's mods. Install the same modpack and versions as the host, then try again.";

    private int deferredClientDisconnectTicks = -1;
    private int deferredServerStopTicks = -1;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // No keys to register
    }

    @Override
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        // 1.8.9 has no @Mod.EventBusSubscriber. EventBus.register(Class) also does not
        // pick up static @SubscribeEvent methods (it walks java.lang.Class instead).
        // Register an instance so GUI hooks actually fire.
        MinecraftForge.EVENT_BUS.register(new steambridge.gui.VanillaGuiIntegration());
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        GuiScreen next = event.gui;
        Minecraft mc = Minecraft.getMinecraft();

        if (next != null) {
            boolean isDisconnect = next instanceof GuiDisconnected;
            boolean isModReject = next.getClass().getName().endsWith("GuiOldSaveLoadConfirm") || next.getClass().getName().endsWith("GuiModReject");

            if (isDisconnect || isModReject) {
                // If we already have an error screen (e.g. detailed FML mod mismatch) and Vanilla tries
                // to open a generic "Disconnected" due to the loopback socket closing, block the generic one!
                boolean isCurrentScreenError = mc.currentScreen instanceof GuiDisconnected ||
                    (mc.currentScreen != null && (mc.currentScreen.getClass().getName().endsWith("GuiOldSaveLoadConfirm") || mc.currentScreen.getClass().getName().endsWith("GuiModReject")));

                if (isDisconnect && isCurrentScreenError) {
                    boolean mayReplaceGeneric = isGenericDisconnectScreen(mc.currentScreen)
                        && !isGenericDisconnectScreen(next);
                    if (!mayReplaceGeneric) {
                        SteamBridgeMod.LOG.info("[SteamBridge] Ignoring secondary GuiDisconnected to preserve original error screen.");
                        event.setCanceled(true);
                        return;
                    }
                }

                // Force all disconnect/error screens to return to Multiplayer Server List, NOT DirectConnect
                try {
                    net.minecraft.client.gui.GuiMultiplayer serverList = new net.minecraft.client.gui.GuiMultiplayer(new net.minecraft.client.gui.GuiMainMenu());
                    if (isDisconnect) {
                        net.minecraftforge.fml.relauncher.ReflectionHelper.setPrivateValue(GuiDisconnected.class, (GuiDisconnected) next, serverList, "parentScreen", "field_146307_h");
                    } else if (isModReject) {
                        try {
                            net.minecraftforge.fml.relauncher.ReflectionHelper.setPrivateValue(net.minecraft.client.gui.GuiYesNo.class, (net.minecraft.client.gui.GuiYesNo) next, serverList, "parentScreen", "field_146313_a");
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }

        handleSteamGuiOpen(event);
    }





    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        steambridge.steam.SteamServer server =
                steambridge.steam.SteamManager.getInstance().getActiveServer();
        if (server != null && server.isRunning()) {
            if (shouldDeferSteamServerStop(mc)) {
                deferredServerStopTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferring Steam host shutdown after client disconnect; screen={} world={} integratedServer={}",
                    screenName(mc.currentScreen), mc.theWorld != null, mc.getIntegratedServer() != null);
            } else {
                deferredServerStopTicks = -1;
                server.stop();
            }
        }

        steambridge.steam.SteamClient client =
                steambridge.steam.SteamManager.getInstance().getActiveClient();
        if (client != null && client.isAlive()) {
            if (shouldShowLoginMismatchHint(mc, client)) {
                deferredClientDisconnectTicks = -1;
                showLoginMismatchHint(mc, client);
            } else if (shouldDeferSteamClientDisconnect(mc, client)) {
                deferredClientDisconnectTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferring Steam client disconnect; possible dimension transfer. screen={} world={} player={} channelOpen={}",
                    screenName(mc.currentScreen), mc.theWorld != null, mc.thePlayer != null, client.isSteamChannelOpen());
            } else {
                deferredClientDisconnectTicks = -1;
                client.disconnect();
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft mc = Minecraft.getMinecraft();
            processDeferredNetworkTeardown(mc);
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client != null && client.isAlive() && mc.theWorld != null && mc.thePlayer != null && !client.isInWorld()) {
                client.onMinecraftWorldJoined(mc.thePlayer.getName(), mc.thePlayer.dimension);
            }

            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server != null && server.isRunning()) {
                if (mc.theWorld == null && mc.getIntegratedServer() == null
                        && !(mc.currentScreen instanceof GuiDownloadTerrain)) {
                    SteamBridgeMod.LOG.info("[SteamBridge] World closed, stopping Steam server...");
                    deferredServerStopTicks = -1;
                    server.stop();
                } else if (shouldStopHostForWorldChange(mc, server)) {
                    SteamBridgeMod.LOG.info(
                        "[SteamBridge] Integrated world changed (was '{}'), stopping leftover Steam host.",
                        server.getWorldKey()
                    );
                    deferredServerStopTicks = -1;
                    server.stop();
                }
            }
        }
    }

    /**
     * Host opened world A via Steam, left to menu, then loaded world B in the same
     * client session. Deferred-stop logic used to treat that as a "transient reload"
     * and keep the old host alive, leaving "Manage Steam Session" on the pause menu.
     */
    private boolean shouldStopHostForWorldChange(Minecraft mc, SteamServer server) {
        if (server == null || !server.isRunning()) {
            return false;
        }
        net.minecraft.server.integrated.IntegratedServer integrated = mc.getIntegratedServer();
        if (integrated == null) {
            return false;
        }
        try {
            String folder = integrated.getFolderName();
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

    @SubscribeEvent
    public void onClientConnectedToServer(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null) {
            client.onMinecraftHandshakeStarted(event.connectionType);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }

        SteamServer server = SteamManager.getInstance().getActiveServer();
        if (server == null || !server.isRunning()) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.playerNetServerHandler == null) {
            return;
        }

        SocketAddress remote = player.playerNetServerHandler.getNetworkManager().getRemoteAddress();
        if (remote instanceof InetSocketAddress) {
            server.attachMinecraftPlayer((InetSocketAddress) remote, player.getName());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && event.player == mc.thePlayer) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client != null) {
                client.onMinecraftWorldJoined(event.player.getName(), event.toDim);
            }
        }
    }



    private void handleSteamGuiOpen(GuiOpenEvent event) {
        GuiScreen next = event.gui;
        SteamClient client = SteamManager.getInstance().getActiveClient();


        // --- Standard Steam GUI hooks --------------------------------------
        if (client != null && next instanceof GuiDisconnected) {
            // Only suppress the spurious UnknownHost screen from the abandoned vanilla
            // GuiConnecting thread, which races in during the early CONNECTING window
            // before our loopback is up. Once STEAM_READY (loopback established) or later,
            // any GuiDisconnected is a real server-side drop and must be shown - otherwise
            // the player is left in limbo with an unresponsive screen.
            if (client.getState() == SteamClient.State.CONNECTING) {
                SteamBridgeMod.LOG.info("[SteamBridge] Ignoring secondary GuiDisconnected from vanilla background thread (e.g. UnknownHost) while Steam connection is negotiating.");
                event.setCanceled(true);
            } else {
                deferredClientDisconnectTicks = -1;
                DisconnectedInfo info = extractDisconnectedInfo((GuiDisconnected) next);
                if (isGenericDisconnectDuringLogin(client, info)) {
                    SteamBridgeMod.LOG.warn(
                        "[SteamBridge] Replacing generic login disconnect with mod mismatch hint. state={} reason='{}' details='{}'",
                        client.getState(),
                        SteamBridgeMod.safeLog(compactText(info.reason)),
                        SteamBridgeMod.safeLog(compactText(info.message))
                    );
                    GuiDisconnected replacement = createModMismatchHintScreen();
                    event.gui = replacement;
                    info = extractDisconnectedInfo(replacement);
                }
                client.onMinecraftDisconnect(info.reason, info.message);
                logDisconnectDetails(info);
                client.disconnect();
            }
        }

        if (client != null && next instanceof GuiDownloadTerrain) {
            client.onMinecraftWorldLoading();
        }
    }

    private void logDisconnectDetails(DisconnectedInfo info) {
        String compact = compactText(info.message);
        if (looksLikeModMismatch(compact)) {
            SteamBridgeMod.LOG.error("[SteamBridge] Mod mismatch detected. {}", SteamBridgeMod.safeLog(compact));
            for (String entry : extractMismatchEntries(info.message)) {
                SteamBridgeMod.LOG.error("[SteamBridge] Mod mismatch detail: {}", SteamBridgeMod.safeLog(entry));
            }
            return;
        }

        SteamBridgeMod.LOG.error(
            "[SteamBridge] Minecraft disconnect screen opened. reason='{}' details='{}'",
            SteamBridgeMod.safeLog(compactText(info.reason)),
            SteamBridgeMod.safeLog(compact)
        );
    }

    private boolean shouldDeferSteamClientDisconnect(Minecraft mc, SteamClient client) {
        return client != null
            && client.isAlive()
            && client.isSteamChannelOpen()
            && isLikelyTransientSteamDisconnectScreen(mc.currentScreen);
    }

    private boolean shouldDeferSteamServerStop(Minecraft mc) {
        return mc.getIntegratedServer() != null
            && isLikelyTransientSteamDisconnectScreen(mc.currentScreen);
    }

    private boolean shouldShowLoginMismatchHint(Minecraft mc, SteamClient client) {
        if (client == null || mc.theWorld != null || mc.thePlayer != null) {
            return false;
        }

        SteamClient.State state = client.getState();
        if (state != SteamClient.State.STEAM_READY && state != SteamClient.State.NEGOTIATING) {
            return false;
        }

        GuiScreen screen = mc.currentScreen;
        return screen == null
            || screen instanceof GuiSteamConnecting
            || isGenericDisconnectScreen(screen);
    }

    private void showLoginMismatchHint(Minecraft mc, SteamClient client) {
        String details = modMismatchHintText();
        SteamBridgeMod.LOG.warn(
            "[SteamBridge] Minecraft connection closed during Steam/Forge login before a detailed disconnect screen appeared. screen={} state={}",
            screenName(mc.currentScreen),
            client.getState()
        );
        client.closeAfterMinecraftFailure("connect.failed", details);
        mc.addScheduledTask(() -> {
            GuiScreen current = mc.currentScreen;
            if (current == null || current instanceof GuiSteamConnecting || isGenericDisconnectScreen(current)) {
                mc.displayGuiScreen(createModMismatchHintScreen());
            }
        });
    }

    private boolean isLikelyTransientSteamDisconnectScreen(GuiScreen screen) {
        return screen == null
            || screen instanceof GuiDownloadTerrain
            || isGalacticraftTravelScreen(screen);
    }

    private boolean isGalacticraftTravelScreen(GuiScreen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("galacticraft")
            || name.contains("asmodeuscore")
            || name.contains("celestial")
            || name.contains("planet");
    }

    private void processDeferredNetworkTeardown(Minecraft mc) {
        if (deferredClientDisconnectTicks >= 0) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client == null || !client.isAlive()) {
                deferredClientDisconnectTicks = -1;
            } else if (shouldCloseDeferredClient(mc, client)) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam client disconnect resolved as real disconnect. screen={} channelOpen={}",
                    screenName(mc.currentScreen), client.isSteamChannelOpen());
                client.disconnect();
            } else if (mc.theWorld != null && mc.thePlayer != null && client.isSteamChannelOpen()) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Preserved Steam client across transient world reload. dim={}",
                    mc.thePlayer.dimension);
            } else if (--deferredClientDisconnectTicks <= 0) {
                deferredClientDisconnectTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Still waiting on transient Steam client disconnect. screen={} channelOpen={}",
                    screenName(mc.currentScreen), client.isSteamChannelOpen());
            }
        }

        if (deferredServerStopTicks >= 0) {
            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server == null || !server.isRunning()) {
                deferredServerStopTicks = -1;
            } else if (shouldStopHostForWorldChange(mc, server)) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred host stop: world changed to a different save, stopping Steam.");
                server.stop();
            } else if (shouldCloseDeferredServer(mc)) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam host shutdown resolved as real disconnect. screen={}",
                    screenName(mc.currentScreen));
                server.stop();
            } else if (mc.getIntegratedServer() != null && mc.theWorld != null
                    && isSameHostedWorld(mc, server)) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info("[SteamBridge] Preserved Steam host across transient world reload.");
            } else if (--deferredServerStopTicks <= 0) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam host shutdown timed out. screen={} integratedServer={}",
                    screenName(mc.currentScreen), mc.getIntegratedServer() != null);
                server.stop();
            }
        }
    }

    private boolean isSameHostedWorld(Minecraft mc, SteamServer server) {
        if (server == null || mc.getIntegratedServer() == null) {
            return false;
        }
        try {
            String folder = mc.getIntegratedServer().getFolderName();
            String hosted = server.getWorldKey();
            return folder != null && hosted != null && folder.equals(hosted);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shouldCloseDeferredClient(Minecraft mc, SteamClient client) {
        if (client == null || !client.isSteamChannelOpen()) {
            return true;
        }
        return isFinalDisconnectScreen(mc.currentScreen);
    }

    private boolean shouldCloseDeferredServer(Minecraft mc) {
        return mc.getIntegratedServer() == null || isFinalDisconnectScreen(mc.currentScreen);
    }

    private boolean isFinalDisconnectScreen(GuiScreen screen) {
        return screen instanceof GuiDisconnected
            || screen instanceof GuiMultiplayer
            || screen instanceof net.minecraft.client.gui.GuiMainMenu;
    }

    private String screenName(GuiScreen screen) {
        return screen != null ? screen.getClass().getName() : "<none>";
    }

    private boolean looksLikeModMismatch(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mod rejection")
            || lower.contains("missing mods")
            || lower.contains("mod mismatch")
            || lower.contains("mods do not match")
            || lower.contains("mods don't match")
            || lower.contains("your mods")
            || lower.contains("requires version")
            || lower.contains("mod is not found");
    }

    private boolean isGenericDisconnectDuringLogin(SteamClient client, DisconnectedInfo info) {
        if (client == null || info == null) {
            return false;
        }

        SteamClient.State state = client.getState();
        if (state != SteamClient.State.STEAM_READY && state != SteamClient.State.NEGOTIATING) {
            return false;
        }

        String reason = compactText(info.reason).toLowerCase(java.util.Locale.ROOT);
        String message = compactText(info.message).toLowerCase(java.util.Locale.ROOT);
        if (looksLikeModMismatch(reason + " " + message)) {
            return false;
        }

        return isGenericDisconnectText(reason) && isGenericDisconnectText(message);
    }

    private boolean isGenericDisconnectScreen(GuiScreen screen) {
        if (!(screen instanceof GuiDisconnected)) {
            return false;
        }
        DisconnectedInfo info = extractDisconnectedInfo((GuiDisconnected) screen);
        return isGenericDisconnectText(compactText(info.reason).toLowerCase(java.util.Locale.ROOT))
            && isGenericDisconnectText(compactText(info.message).toLowerCase(java.util.Locale.ROOT));
    }

    private boolean isGenericDisconnectText(String text) {
        return text == null
            || text.isEmpty()
            || text.equals("disconnected")
            || text.equals("disconnect")
            || text.equals("disconnect.genericreason")
            || text.equals("disconnect.endofstream")
            || text.equals("connect.failed");
    }

    private GuiDisconnected createModMismatchHintScreen() {
        return new GuiDisconnected(
            new GuiMultiplayer(new net.minecraft.client.gui.GuiMainMenu()),
            "connect.failed",
            new ChatComponentTranslation(MOD_MISMATCH_HINT_KEY)
        );
    }

    private String modMismatchHintText() {
        try {
            String t = I18n.format(MOD_MISMATCH_HINT_KEY);
            if (t != null && !t.equals(MOD_MISMATCH_HINT_KEY)) {
                return t;
            }
        } catch (Exception ignored) {}
        return MOD_MISMATCH_HINT_FALLBACK;
    }

    private java.util.List<String> extractMismatchEntries(String text) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return entries;
        }

        for (String line : text.replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("mod rejection")) {
                continue;
            }
            entries.add(trimmed);
        }
        return entries;
    }

    private String compactText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace('\r', '\n')
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private DisconnectedInfo extractDisconnectedInfo(GuiDisconnected gui) {
        try {
            Field reasonField = ReflectionHelper.findField(GuiDisconnected.class, "reason", "field_146306_a");
            Field messageField = ReflectionHelper.findField(GuiDisconnected.class, "message", "field_146304_f");
            String reason = (String) reasonField.get(gui);
            IChatComponent message = (IChatComponent) messageField.get(gui);
            return new DisconnectedInfo(
                reason != null ? reason : "",
                message != null ? message.getUnformattedText() : ""
            );
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamBridge] Failed to inspect GuiDisconnected details: {}", t.toString());
            return new DisconnectedInfo("disconnect", "");
        }
    }

    private static final class DisconnectedInfo {
        private final String reason;
        private final String message;

        private DisconnectedInfo(String reason, String message) {
            this.reason = reason;
            this.message = message;
        }
    }
}
