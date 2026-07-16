/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SteamClient {

    public enum State { IDLE, CONNECTING, STEAM_READY, NEGOTIATING, IN_WORLD, FAILED }

    private volatile State state = State.IDLE;
    private volatile String statusMsg = "";
    private final AtomicBoolean alive = new AtomicBoolean(false);

    private volatile SteamID hostSteamID;
    private volatile int connectionHandle = 0;

    /** Screen shown when connection was initiated - passed to the login net handler. */
    private volatile Screen connectingScreen;

    /**
     * Minecraft login connection created by {@link SteamTransport#connectClientToLoopback}.
     * Must be ticked like ConnectScreen.connection or handleDisconnection never runs.
     */
    private volatile ClientConnection pendingConnection;

    private volatile CountDownLatch connectLatch = new CountDownLatch(1);

    public void connect(SteamID host) {
        connect(host, null);
    }

    public void connect(SteamID host, Screen currentScreen) {
        // Atomic claim: two racing connect() calls must not both proceed.
        if (!alive.compareAndSet(false, true)) {
            SteamBridgeMod.LOG.warn("[SteamClient] connect() called while already active.");
            return;
        }

        connectLatch = new CountDownLatch(1);
        connectingScreen = currentScreen;
        hostSteamID = host;
        state = State.CONNECTING;
        statusMsg = i18n("steambridge.status.connecting", "Connecting through Steam Relay...");
        SteamManager.getInstance().setActiveClient(this);

        long hostKey = SteamNativeHandle.getNativeHandle(host);
        SteamBridgeMod.LOG.info("[SteamClient] Connecting to host SteamID={}", hostKey);

        Thread thread = new Thread(() -> doConnect(host), "SteamBridge-ClientConnect");
        thread.setDaemon(true);
        thread.start();
    }

    public void disconnect() {
        closeSteamTransport("Client disconnected", true);
    }

    public void closeAfterMinecraftFailure(String reason, String details) {
        onMinecraftDisconnect(reason, details);
        closeSteamTransport(compact(details).isEmpty() ? "Minecraft login rejected" : compact(details), false);
    }

    private void closeSteamTransport(String steamCloseReason, boolean resetStatus) {
        SteamBridgeMod.LOG.info("[SteamClient] closeSteamTransport() called. wasAlive={} resetStatus={}",
            alive.getAndSet(false), resetStatus);

        if (resetStatus) {
            state = State.IDLE;
            statusMsg = "Disconnected.";
        }
        connectLatch.countDown();
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient();
        SteamManager.getInstance().setActiveClient(null);

        pendingConnection = null;

        if (connectionHandle != 0) {
            SteamManager.getInstance().unregisterLoopback(connectionHandle);
            SteamManager.getInstance().closeConnection(
                connectionHandle, SteamSocketsApi.APP_CLOSE_NORMAL, safeText(steamCloseReason, "Client disconnected")
            );
        }

        connectionHandle = 0;
        SteamBridgeMod.LOG.info("[SteamClient] Steam transport closed.");
    }

    public void setPendingConnection(ClientConnection connection) {
        this.pendingConnection = connection;
    }

    public ClientConnection getPendingConnection() {
        return pendingConnection;
    }

    public void setStatusMsg(String msg) {
        if (msg != null && !msg.isEmpty()) {
            this.statusMsg = msg;
        }
    }

    /**
     * Mirrors {@link ConnectScreen#tick}: drive the login connection each client tick.
     */
    public void tickPendingConnection() {
        ClientConnection connection = pendingConnection;
        if (connection == null) {
            return;
        }
        if (connection.isOpen()) {
            connection.tick();
        } else {
            connection.handleDisconnection();
            pendingConnection = null;
        }
    }

    private void doConnect(SteamID host) {
        long hostKey = SteamNativeHandle.getNativeHandle(host);
        try {
            connectionHandle = SteamManager.getInstance().connectP2P(
                host, steambridge.SteamBridgeConfig.virtualPort);
            if (connectionHandle == 0) {
                fail(i18n("steambridge.status.fail_create", "Steam refused to create a relay connection."));
                return;
            }

            final long remoteSteamID = hostKey;
            final int conn = connectionHandle;

            statusMsg = i18n("steambridge.status.waiting_route", "Waiting for Steam route to host...");
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
                fail(i18n("steambridge.status.fail_timeout", "Timeout: Steam route to host did not become ready within 30 seconds."));
                return;
            }

            if (!alive.get()) {
                SteamBridgeMod.LOG.info(
                    "[SteamClient] Connect attempt ended before pipeline activation. conn={} state={}",
                    conn, state
                );
                return;
            }

            // Start UDP proxy here, not in the Steam callback thread.
            // Calling connectP2P() from the callback thread blocked it long enough
            // to delay the Forge handshake and cause a 30-second login timeout on
            // the first connection attempt. Starting it here frees the callback
            // thread while still running well before SVC receives its secret.
            if (SteamBridgeConfig.interceptUdp) {
                steambridge.proxy.SteamUdpProxy.getInstance().startClient(hostSteamID);
            }

            statusMsg = i18n("steambridge.status.path_ready", "Steam path ready - activating pipeline...");
            final Screen screen = connectingScreen;

            java.net.SocketAddress proxyAddr = SteamTransport.allocateClientLoopbackEndpoint(conn);
            if (proxyAddr == null) {
                fail(i18n("steambridge.status.fail_proxy", "Failed to start loopback proxy for connection."));
                return;
            }

            SteamBridgeMod.LOG.info("[SteamClient] Loopback proxy ready: conn={} steamID={} addr={}",
                    conn, remoteSteamID, proxyAddr);

            // Vanilla ConnectScreen does connect + handshake on a worker thread, not main.
            final java.net.SocketAddress finalProxyAddr = proxyAddr;
            final Screen finalScreen = screen;
            Thread mcConnect = new Thread(() -> {
                try {
                    boolean ok2 = SteamTransport.connectClientToLoopback(
                            conn, remoteSteamID, finalProxyAddr, finalScreen, SteamClient.this);
                    if (ok2) {
                        state = State.STEAM_READY;
                        statusMsg = i18n("steambridge.status.steam_ready",
                                "Steam path ready - waiting for Minecraft login...");
                        SteamBridgeMod.LOG.info(
                                "[SteamClient] Loopback mode active - Steam transport is ready.");
                    } else {
                        SteamBridgeMod.LOG.error(
                                "[SteamClient] Loopback connect to {} failed.", finalProxyAddr);
                        fail(i18n("steambridge.status.fail_proxy",
                                "Failed to connect to loopback proxy ") + finalProxyAddr);
                    }
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error(
                            "[SteamClient] Loopback connect failed: {}", e.getMessage(), e);
                    fail("Loopback connect error: " + e.getMessage());
                }
            }, "SteamBridge-MC-Connect");
            mcConnect.setDaemon(true);
            mcConnect.start();

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
                ? i18n("steambridge.status.route_relay", "Relay path ready - activating channel...")
                : i18n("steambridge.status.route_direct", "Direct path ready - activating channel...");
            connectLatch.countDown();
            return;
        }

        if (status.isTerminal()) {
            String error = status.getLastError().isEmpty()
                ? status.describeState()
                : status.getLastError();

            // If the server sent a raw localization key, translate it on the client side
            error = i18n(error, error);

            if (alive.get()) {
                fail(i18n("steambridge.status.fail_closed", "Steam connection closed: ") + error);
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
        statusMsg = i18n("steambridge.status.handshake", "Steam ready - Minecraft/Forge handshake in progress...");
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
        statusMsg = i18n("steambridge.status.loading_world", "Login accepted - loading world...");
        SteamBridgeMod.LOG.info("[SteamClient] Minecraft accepted the login. Loading world...");
    }

    public void onMinecraftWorldJoined(String playerName, int dimension) {
        if (!alive.get()) {
            return;
        }

        state = State.IN_WORLD;
        statusMsg = i18n("steambridge.status.in_world", "Connected as ") + safeText(playerName, "?") + " (dim " + dimension + ")";
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

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            Screen current = mc.currentScreen;
            if (current instanceof DownloadingTerrainScreen || current instanceof ConnectScreen) {
                mc.openScreen(new DisconnectedScreen(
                    connectingScreen != null ? connectingScreen : new TitleScreen(),
                    new TranslatableText("connect.failed"),
                    new LiteralText(msg)
                ));
            }
        });
    }

    private static boolean looksLikeModMismatch(String reason, String details) {
        String text = (reason + "\n" + details).toLowerCase(Locale.ROOT);
        return text.contains("mod rejection")
            || text.contains("missing mods")
            || text.contains("mod mismatch")
            || text.contains("mods do not match")
            || text.contains("mods don't match")
            || text.contains("your mods")
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
        static final String RED = "§c";
    }

    /** Returns the I18n translation for {@code key}, or {@code fallback} if unavailable. */
    private static String i18n(String key, String fallback) {
        try {
            if (net.minecraft.client.resource.language.I18n.hasTranslation(key)) {
                return net.minecraft.client.resource.language.I18n.translate(key);
            }
        } catch (Exception ignored) {
            // Minecraft not yet fully initialised - use English fallback.
        }
        return fallback;
    }
}
