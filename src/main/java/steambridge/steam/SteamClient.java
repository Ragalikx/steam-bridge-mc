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
package steambridge.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import steambridge.SteamBridgeMod;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SteamClient {

    public enum State { IDLE, CONNECTING, STEAM_READY, NEGOTIATING, IN_WORLD, FAILED }

    private volatile State state = State.IDLE;
    private volatile String statusMsg = "";
    private final AtomicBoolean alive = new AtomicBoolean(false);

    private SteamID hostSteamID;
    private volatile int connectionHandle = 0;

    /** Screen shown when connection was initiated - passed to NetHandlerLoginClient. */
    private volatile net.minecraft.client.gui.GuiScreen connectingScreen;

    private volatile CountDownLatch connectLatch = new CountDownLatch(1);

    public void connect(SteamID host) {
        connect(host, null);
    }

    public void connect(SteamID host, net.minecraft.client.gui.GuiScreen currentScreen) {
        if (alive.get()) {
            SteamBridgeMod.LOG.warn("[SteamClient] connect() called while already active.");
            return;
        }

        connectLatch = new CountDownLatch(1);
        connectingScreen = currentScreen;
        hostSteamID = host;
        alive.set(true);
        state = State.CONNECTING;
        statusMsg = "Connecting through Steam Relay...";
        SteamManager.getInstance().setActiveClient(this);

        long hostKey = SteamNativeHandle.getNativeHandle(host);
        SteamBridgeMod.LOG.info("[SteamClient] Connecting to host SteamID={}", hostKey);

        Thread thread = new Thread(() -> doConnect(host), "SteamBridge-ClientConnect");
        thread.setDaemon(true);
        thread.start();
    }

    public void disconnect() {
        boolean wasAlive = alive.getAndSet(false);
        SteamBridgeMod.LOG.info("[SteamClient] disconnect() called. wasAlive={}", wasAlive);

        state = State.IDLE;
        statusMsg = "Disconnected.";
        connectLatch.countDown();
        SteamManager.getInstance().setActiveClient(null);

        if (connectionHandle != 0) {
            SteamManager.getInstance().unregisterLoopback(connectionHandle);
            SteamManager.getInstance().closeConnection(
                connectionHandle, SteamSocketsApi.APP_CLOSE_NORMAL, "Client disconnected"
            );
        }

        connectionHandle = 0;
        SteamBridgeMod.LOG.info("[SteamClient] Disconnected.");
    }

    private void doConnect(SteamID host) {
        long hostKey = SteamNativeHandle.getNativeHandle(host);
        try {
            connectionHandle = SteamManager.getInstance().connectP2P(
                host, steambridge.SteamBridgeConfig.virtualPort);
            if (connectionHandle == 0) {
                fail("Steam refused to create a relay connection.");
                return;
            }

            final long remoteSteamID = hostKey;
            final int conn = connectionHandle;

            statusMsg = "Waiting for Steam route to host...";
            SteamBridgeMod.LOG.info(
                "[SteamClient] ConnectP2P started. hostSteamID={} conn={}",
                hostKey, conn
            );

            boolean connected = connectLatch.await(30, TimeUnit.SECONDS);
            SteamBridgeMod.LOG.info(
                "[SteamClient] connectLatch.await() returned: connected={} alive={} conn={}",
                connected, alive.get(), conn
            );

            if (!connected) {
                fail("Timeout: Steam route to host did not become ready within 30 seconds.");
                return;
            }

            if (!alive.get()) {
                SteamBridgeMod.LOG.info(
                    "[SteamClient] Connect attempt ended before pipeline activation. conn={} state={}",
                    conn, state
                );
                return;
            }

            statusMsg = "Steam path ready - activating Netty pipeline...";
            final net.minecraft.client.gui.GuiScreen screen = connectingScreen;

            int proxyPort = SteamTransport.allocateClientLoopbackPort(conn);
            if (proxyPort < 0) {
                fail("Failed to start loopback proxy for connection.");
                return;
            }

            // Connect Minecraft to the proxy port on the MC main thread.
            final int finalProxyPort = proxyPort;
            net.minecraft.client.Minecraft mc2 = net.minecraft.client.Minecraft.getMinecraft();
            mc2.addScheduledTask(() -> {
                try {
                    boolean ok2 = SteamTransport.connectClientToLoopback(
                            conn, remoteSteamID, finalProxyPort, screen);
                    if (ok2) {
                        state = State.STEAM_READY;
                        statusMsg = "Steam path ready - waiting for Minecraft login...";
                        SteamBridgeMod.LOG.info("[SteamClient] Loopback mode active — Steam transport is ready.");
                    } else {
                        SteamBridgeMod.LOG.error("[SteamClient] Loopback connect to port {} failed.", finalProxyPort);
                        fail("Failed to connect to loopback proxy port " + finalProxyPort);
                    }
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamClient] Loopback connect failed: {}", e.getMessage(), e);
                    fail("Loopback connect error: " + e.getMessage());
                }
            });

            SteamBridgeMod.LOG.info("[SteamClient] Loopback proxy ready: conn={} steamID={}", conn, remoteSteamID);

        } catch (Exception e) {
            if (alive.get()) {
                SteamBridgeMod.LOG.error(
                    "[SteamClient] doConnect error: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage(), e
                );
                fail("Connection error: " + e.getMessage());
            }
        }
    }

    public void onConnectionStatusChanged(int connection, long remoteSteamID, SteamConnectionStatus status, int oldState) {
        if (!ownsConnection(connection, remoteSteamID)) return;

        SteamBridgeMod.LOG.info(
            "[SteamClient] Connection callback: conn={} steamID={} state={} route={} old={}",
            connection, remoteSteamID, status.describeState(), status.describeRoute(),
            SteamSocketsApi.stateName(oldState)
        );

        if (status.getState() == SteamSocketsApi.STATE_CONNECTED) {
            statusMsg = status.isUsingRelay()
                ? "Relay path ready - activating channel..."
                : "Direct path ready - activating channel...";
            connectLatch.countDown();
            return;
        }

        if (status.isTerminal()) {
            String error = status.getLastError().isEmpty()
                ? status.describeState()
                : status.getLastError();

            if (alive.get()) {
                fail("Steam connection closed: " + error);
            }
        }
    }

    public void onMessageReceived(int connection, byte[] data) {
        if (connection != connectionHandle) return;

        // Messages are delivered directly to loopback by SteamManager.
        SteamBridgeMod.LOG.warn("[SteamClient] DATA arrived for conn={} but no handler active.", connection);
    }

    /** Returns true if the underlying loopback connection is still open. */
    public boolean isSteamChannelOpen() {
        return connectionHandle != 0;
    }

    public boolean isConnected() {
        return state == State.STEAM_READY || state == State.IN_WORLD;
    }

    public boolean isConnectingOrConnected() {
        return state == State.CONNECTING || state == State.STEAM_READY || state == State.IN_WORLD;
    }

    public State getState() {
        return state;
    }

    public String getStatusMsg() {
        return statusMsg;
    }

    public int getLocalPort() {
        return -1; // unused in SteamChannel mode, kept for API compatibility
    }

    public SteamID getHostSteamID() {
        return hostSteamID;
    }

    public boolean isAlive() {
        return alive.get();
    }

    public boolean isInWorld() {
        return state == State.IN_WORLD;
    }

    public void onMinecraftHandshakeStarted(String connectionType) {
        if (!alive.get() || state == State.IDLE || state == State.FAILED) {
            return;
        }

        if (state != State.IN_WORLD) {
            state = State.NEGOTIATING;
        }
        statusMsg = "Steam ready - Minecraft/Forge handshake in progress...";
        SteamBridgeMod.LOG.info(
            "[SteamClient] Minecraft handshake started. connectionType={} conn={}",
            safeText(connectionType, "unknown"),
            connectionHandle
        );
    }

    public void onMinecraftWorldLoading() {
        if (!alive.get() || state == State.IDLE || state == State.FAILED || state == State.IN_WORLD) {
            return;
        }

        state = State.NEGOTIATING;
        statusMsg = "Login accepted - loading world...";
        SteamBridgeMod.LOG.info("[SteamClient] Minecraft accepted the login. Loading world...");
    }

    public void onMinecraftWorldJoined(String playerName, int dimension) {
        if (!alive.get()) {
            return;
        }

        state = State.IN_WORLD;
        statusMsg = "Connected as " + safeText(playerName, "?") + " (dim " + dimension + ")";
        SteamBridgeMod.LOG.info(
            "[SteamClient] Minecraft world joined successfully. player={} dimension={} conn={}",
            safeText(playerName, "?"),
            dimension,
            connectionHandle
        );
    }

    public void onMinecraftDisconnect(String reason, String details) {
        String cleanReason = safeText(reason, "Disconnected");
        String cleanDetails = compact(details);

        if (looksLikeModMismatch(cleanReason, cleanDetails)) {
            SteamBridgeMod.LOG.error(
                "[SteamClient] Minecraft/Forge login rejected due to mod mismatch. reason='{}' details='{}'",
                SteamBridgeMod.safeLog(cleanReason),
                SteamBridgeMod.safeLog(cleanDetails)
            );
        } else {
            SteamBridgeMod.LOG.error(
                "[SteamClient] Minecraft disconnect while using Steam transport. reason='{}' details='{}'",
                SteamBridgeMod.safeLog(cleanReason),
                SteamBridgeMod.safeLog(cleanDetails)
            );
        }

        if (state != State.IN_WORLD && state != State.IDLE) {
            state = State.FAILED;
            statusMsg = TextColors.RED + cleanReason
                + (cleanDetails.isEmpty() ? "" : " - " + cleanDetails);
        }
    }

    public SteamConnectionStatus getConnectionStatus() {
        return connectionHandle != 0
            ? SteamManager.getInstance().getConnectionStatusByHandle(connectionHandle)
            : SteamManager.getInstance().getConnectionStatus(hostSteamID);
    }

    public boolean ownsConnection(int connection, long remoteSteamID) {
        if (connectionHandle != 0 && connection == connectionHandle) {
            return true;
        }
        return hostSteamID != null && remoteSteamID == SteamNativeHandle.getNativeHandle(hostSteamID)
            && alive.get();
    }

    private void fail(String msg) {
        SteamBridgeMod.LOG.error("[SteamClient] FAIL: {}", msg);
        state = State.FAILED;
        statusMsg = TextColors.RED + msg;
        alive.set(false);
        connectLatch.countDown();
        SteamManager.getInstance().setActiveClient(null);

        if (connectionHandle != 0) {
            SteamManager.getInstance().unregisterLoopback(connectionHandle);
            SteamManager.getInstance().closeConnection(
                connectionHandle, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, msg
            );
        }

        connectionHandle = 0;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            net.minecraft.client.gui.GuiScreen current = mc.currentScreen;
            if (current instanceof net.minecraft.client.gui.GuiDownloadTerrain ||
                current instanceof net.minecraft.client.multiplayer.GuiConnecting) {
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(
                    connectingScreen != null ? connectingScreen : new net.minecraft.client.gui.GuiMainMenu(),
                    "connect.failed",
                    new net.minecraft.util.text.TextComponentString(msg)
                ));
            }
        });
    }

    private static boolean looksLikeModMismatch(String reason, String details) {
        String text = (reason + "\n" + details).toLowerCase(Locale.ROOT);
        return text.contains("mod rejection")
            || text.contains("missing mods")
            || text.contains("requires version")
            || text.contains("mod is not found");
    }

    private static String safeText(String value, String fallback) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace('\r', '\n')
            .replace("\n\n", "\n")
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static class TextColors {
        static final String RED = "\u00a7c";
    }
}

