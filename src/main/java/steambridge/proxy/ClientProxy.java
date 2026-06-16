/*
 * Copyright (c) 2019-2026 Ragalikx
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;
import steambridge.steam.SteamClient;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
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

    private int deferredClientDisconnectTicks = -1;
    private int deferredServerStopTicks = -1;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // No keys to register
    }

    @Override
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
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
                    screenName(mc.currentScreen), mc.world != null, mc.getIntegratedServer() != null);
            } else {
                deferredServerStopTicks = -1;
                server.stop();
            }
        }

        steambridge.steam.SteamClient client =
                steambridge.steam.SteamManager.getInstance().getActiveClient();
        if (client != null && client.isAlive()) {
            if (shouldDeferSteamClientDisconnect(mc, client)) {
                deferredClientDisconnectTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferring Steam client disconnect; possible dimension transfer. screen={} world={} player={} channelOpen={}",
                    screenName(mc.currentScreen), mc.world != null, mc.player != null, client.isSteamChannelOpen());
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
            if (client != null && client.isAlive() && mc.world != null && mc.player != null && !client.isInWorld()) {
                client.onMinecraftWorldJoined(mc.player.getName(), mc.player.dimension);
            }

            SteamServer server = SteamManager.getInstance().getActiveServer();
            if (server != null && server.isRunning()) {
                if (mc.world == null && mc.getIntegratedServer() == null && !(mc.currentScreen instanceof GuiDownloadTerrain)) {
                    SteamBridgeMod.LOG.info("[SteamBridge] World closed, stopping Steam server...");
                    server.stop();
                }
            }
        }
    }

    @SubscribeEvent
    public void onClientConnectedToServer(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        SteamClient client = SteamManager.getInstance().getActiveClient();
        if (client != null) {
            client.onMinecraftHandshakeStarted(event.getConnectionType());
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
        if (player.connection == null) {
            return;
        }

        SocketAddress remote = player.connection.getNetworkManager().getRemoteAddress();
        if (remote instanceof InetSocketAddress) {
            server.attachMinecraftPlayer((InetSocketAddress) remote, player.getName());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null && event.player == mc.player) {
            SteamClient client = SteamManager.getInstance().getActiveClient();
            if (client != null) {
                client.onMinecraftWorldJoined(event.player.getName(), event.toDim);
            }
        }
    }



    private void handleSteamGuiOpen(GuiOpenEvent event) {
        GuiScreen next = event.getGui();
        SteamClient client = SteamManager.getInstance().getActiveClient();


        // --- Standard Steam GUI hooks --------------------------------------
        if (client != null && next instanceof GuiDisconnected) {
            if (client.getState() == SteamClient.State.CONNECTING || client.getState() == SteamClient.State.STEAM_READY) {
                SteamBridgeMod.LOG.info("[SteamBridge] Ignoring secondary GuiDisconnected from vanilla background thread (e.g. UnknownHost) while Steam connection is negotiating.");
                event.setCanceled(true);
            } else {
                deferredClientDisconnectTicks = -1;
                DisconnectedInfo info = extractDisconnectedInfo((GuiDisconnected) next);
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
            } else if (mc.world != null && mc.player != null && client.isSteamChannelOpen()) {
                deferredClientDisconnectTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Preserved Steam client across transient world reload. dim={}",
                    mc.player.dimension);
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
            } else if (shouldCloseDeferredServer(mc)) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Deferred Steam host shutdown resolved as real disconnect. screen={}",
                    screenName(mc.currentScreen));
                server.stop();
            } else if (mc.getIntegratedServer() != null && mc.world != null) {
                deferredServerStopTicks = -1;
                SteamBridgeMod.LOG.info("[SteamBridge] Preserved Steam host across transient world reload.");
            } else if (--deferredServerStopTicks <= 0) {
                deferredServerStopTicks = TRANSIENT_DISCONNECT_GRACE_TICKS;
                SteamBridgeMod.LOG.info(
                    "[SteamBridge] Still waiting on transient Steam host disconnect. screen={} integratedServer={}",
                    screenName(mc.currentScreen), mc.getIntegratedServer() != null);
            }
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
            || lower.contains("requires version")
            || lower.contains("mod is not found");
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
            ITextComponent message = (ITextComponent) messageField.get(gui);
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
