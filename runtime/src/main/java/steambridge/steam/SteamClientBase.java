/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SteamClientBase<T> implements SteamGameClient {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    protected abstract SteamRuntime<?, ?> runtime();

    protected abstract void activateLoopback(int conn, long remoteSteamID, T screen);

    protected abstract void showFailure(String message);

    protected abstract String i18n(String key, String fallback);

    protected void clearMinecraftConnection() {}

    private final steambridge.core.Attempt attempt = new steambridge.core.Attempt();
    private final AtomicBoolean started = new AtomicBoolean();

    public enum State {
        IDLE,
        CONNECTING,
        STEAM_READY,
        NEGOTIATING,
        IN_WORLD,
        FAILED
    }

    protected volatile State state = State.IDLE;
    protected volatile String statusMsg = "";
    protected final AtomicBoolean alive = new AtomicBoolean(false);

    protected volatile SteamID hostSteamID;
    protected volatile int connectionHandle = 0;

    protected volatile T connectingScreen;

    private volatile CountDownLatch connectLatch = new CountDownLatch(1);

    public void connect(SteamID host, T currentScreen) {

        if (attempt.isCancelled()
                || !started.compareAndSet(false, true)
                || !alive.compareAndSet(false, true)) {
            LOG.warn("[SteamClient] connect() called while already active.");
            return;
        }

        connectLatch = new CountDownLatch(1);
        connectingScreen = currentScreen;
        hostSteamID = host;
        state = State.CONNECTING;
        statusMsg = i18n("steambridge.status.connecting", "Connecting through Steam Relay...");
        runtime().setActiveClient(this);

        long hostKey = SteamNativeHandle.getNativeHandle(host);
        LOG.info("[SteamClient] Connecting to host SteamID={}", hostKey);

        Thread thread = new Thread(() -> doConnect(host), "SteamBridge-ClientConnect");
        thread.setDaemon(true);
        thread.start();
    }

    public void disconnect() {
        closeSteamTransport("Client disconnected", true);
    }

    public void closeAfterMinecraftFailure(String reason, String details) {
        onMinecraftDisconnect(reason, details);
        closeSteamTransport(
                compact(details).isEmpty() ? "Minecraft login rejected" : compact(details), false);
    }

    private void closeSteamTransport(String steamCloseReason, boolean resetStatus) {
        attempt.cancel();
        runtime().clearActiveClient(this);
        LOG.info(
                "[SteamClient] closeSteamTransport() called. wasAlive={} resetStatus={}",
                alive.getAndSet(false),
                resetStatus);

        if (resetStatus) {
            state = State.IDLE;
            statusMsg = "Disconnected.";
        }
        connectLatch.countDown();
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient(this);
        runtime().clearActiveClient(this);

        if (connectionHandle != 0) {
            runtime().unregisterLoopback(connectionHandle);
            runtime()
                    .closeConnection(
                            connectionHandle,
                            SteamSocketsApi.APP_CLOSE_NORMAL,
                            safeText(steamCloseReason, "Client disconnected"));
        }

        connectionHandle = 0;
        clearMinecraftConnection();
        LOG.info("[SteamClient] Steam transport closed.");
    }

    private void doConnect(SteamID host) {
        if (!isAttemptActive()) return;
        long hostKey = SteamNativeHandle.getNativeHandle(host);
        try {
            connectionHandle =
                    runtime()
                            .connectP2P(
                                    host,
                                    steambridge.SteamBridgeConfig.settings().virtualPort,
                                    this,
                                    steambridge.core.ConnectionRegistry.Kind.GAME);
            final int createdHandle = connectionHandle;
            if (!isAttemptActive()) {
                runtime()
                        .closeConnection(
                                createdHandle,
                                SteamSocketsApi.APP_CLOSE_NORMAL,
                                "Cancelled attempt");
                connectionHandle = 0;
                return;
            }
            if (connectionHandle == 0) {
                fail(
                        i18n(
                                "steambridge.status.fail_create",
                                "Steam refused to create a relay connection."));
                return;
            }

            final long remoteSteamID = hostKey;
            final int conn = connectionHandle;

            statusMsg =
                    i18n("steambridge.status.waiting_route", "Waiting for Steam route to host...");
            LOG.info("[SteamClient] ConnectP2P started. hostSteamID={} conn={}", hostKey, conn);

            boolean connected =
                    connectLatch.await(
                            Math.max(
                                            5,
                                            Math.min(
                                                    120,
                                                    steambridge.SteamBridgeConfig.settings()
                                                            .timeoutInitialSec))
                                    + 5,
                            TimeUnit.SECONDS);
            LOG.info(
                    "[SteamClient] connectLatch.await() returned: connected={} alive={} conn={}",
                    connected,
                    alive.get(),
                    conn);

            if (!connected) {
                fail(
                        i18n(
                                "steambridge.status.fail_timeout",
                                "Timeout: Steam route to host did not become ready."));
                return;
            }

            if (!isAttemptActive()) {
                runtime()
                        .closeConnection(
                                conn, SteamSocketsApi.APP_CLOSE_NORMAL, "Cancelled attempt");
                LOG.info(
                        "[SteamClient] Connect attempt ended before pipeline activation. conn={}"
                                + " state={}",
                        conn,
                        state);
                return;
            }

            if (steambridge.SteamBridgeConfig.settings().interceptUdp) {
                steambridge.proxy.SteamUdpProxy.getInstance().startClient(hostSteamID, this);
            }
            statusMsg =
                    i18n(
                            "steambridge.status.path_ready",
                            "Steam path ready - activating pipeline...");
            final T screen = connectingScreen;

            activateLoopback(conn, remoteSteamID, screen);

            LOG.info("[SteamClient] Loopback proxy ready: conn={} steamID={}", conn, remoteSteamID);

        } catch (Exception e) {
            if (alive.get()) {
                LOG.error(
                        "[SteamClient] doConnect error: {} - {}",
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
                fail("Connection error: " + e.getMessage());
            }
        }
    }

    public void onConnectionStatusChanged(
            int connection, long remoteSteamID, SteamConnectionStatus status, int oldState) {
        if (runtime().ownsClientConnection(connection, this) && connectionHandle == 0) {
            connectionHandle = connection;
        }
        if (!ownsConnection(connection, remoteSteamID)) return;

        LOG.info(
                "[SteamClient] Connection callback: conn={} steamID={} state={} route={} old={}",
                connection,
                remoteSteamID,
                status.describeState(),
                status.describeRoute(),
                SteamSocketsApi.stateName(oldState));

        if (status.getState() == SteamSocketsApi.STATE_CONNECTED) {
            statusMsg =
                    status.isUsingRelay()
                            ? i18n(
                                    "steambridge.status.route_relay",
                                    "Relay path ready - activating channel...")
                            : i18n(
                                    "steambridge.status.route_direct",
                                    "Direct path ready - activating channel...");
            connectLatch.countDown();
            return;
        }

        if (status.isTerminal()) {
            String error =
                    status.getLastError().isEmpty()
                            ? status.describeState()
                            : status.getLastError();

            error = i18n(error, error);

            if (alive.get()) {
                fail(i18n("steambridge.status.fail_closed", "Steam connection closed: ") + error);
            }
        }
    }

    public boolean isSteamChannelOpen() {
        return connectionHandle != 0;
    }

    public State getState() {
        return state;
    }

    public String getStatusMsg() {
        return statusMsg;
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
        statusMsg =
                i18n(
                        "steambridge.status.handshake",
                        "Steam ready - Minecraft/Forge handshake in progress...");
        LOG.info(
                "[SteamClient] Minecraft handshake started. connectionType={} conn={}",
                safeText(connectionType, "unknown"),
                connectionHandle);
    }

    public void onMinecraftWorldLoading() {
        if (!alive.get()
                || state == State.IDLE
                || state == State.FAILED
                || state == State.IN_WORLD) {
            return;
        }

        state = State.NEGOTIATING;
        statusMsg = i18n("steambridge.status.loading_world", "Login accepted - loading world...");
        LOG.info("[SteamClient] Minecraft accepted the login. Loading world...");
    }

    public void onMinecraftWorldJoined(String playerName, int dimension) {
        if (!alive.get()) {
            return;
        }

        state = State.IN_WORLD;
        statusMsg =
                i18n("steambridge.status.in_world", "Connected as ")
                        + safeText(playerName, "?")
                        + " (dim "
                        + dimension
                        + ")";
        LOG.info(
                "[SteamClient] Minecraft world joined successfully. player={} dimension={} conn={}",
                steambridge.core.LogText.safe(safeText(playerName, "?")),
                dimension,
                connectionHandle);
    }

    public void onMinecraftDisconnect(String reason, String details) {
        String cleanReason = safeText(reason, "Disconnected");
        String cleanDetails = compact(details);

        if (looksLikeModMismatch(cleanReason, cleanDetails)) {
            LOG.error(
                    "[SteamClient] Minecraft/Forge login rejected due to mod mismatch. reason='{}'"
                            + " details='{}'",
                    steambridge.core.LogText.safe(cleanReason),
                    steambridge.core.LogText.safe(cleanDetails));
        } else {
            LOG.error(
                    "[SteamClient] Minecraft disconnect while using Steam transport. reason='{}'"
                            + " details='{}'",
                    steambridge.core.LogText.safe(cleanReason),
                    steambridge.core.LogText.safe(cleanDetails));
        }

        if (state != State.IN_WORLD && state != State.IDLE) {
            state = State.FAILED;
            statusMsg =
                    TextColors.RED
                            + cleanReason
                            + (cleanDetails.isEmpty() ? "" : " - " + cleanDetails);
        }
    }

    protected final boolean isFailureCurrent() {
        return state == State.FAILED
                && (runtime().getActiveClient() == null || runtime().getActiveClient() == this);
    }

    protected final void markSteamReady() {
        if (!isAttemptActive() || state == State.NEGOTIATING || state == State.IN_WORLD) return;
        state = State.STEAM_READY;
        statusMsg =
                i18n(
                        "steambridge.status.steam_ready",
                        "Steam path ready - waiting for Minecraft login...");
    }

    public void connect(SteamID host) {
        connect(host, null);
    }

    public SteamID getHostSteamID() {
        return hostSteamID;
    }

    public boolean isConnected() {
        return state == State.IN_WORLD;
    }

    public boolean isConnectingOrConnected() {
        return isAlive();
    }

    public void setStatusMsg(String value) {
        statusMsg = value;
    }

    public SteamConnectionStatus getConnectionStatus() {
        return runtime().getConnectionStatus(hostSteamID);
    }

    public void onMessageReceived(int handle, byte[] data) {}

    public boolean ownsConnection(int connection, long remoteSteamID) {
        return isAttemptActive() && runtime().ownsClientConnection(connection, this);
    }

    protected final void fail(String msg) {
        if (!alive.compareAndSet(true, false)) return;
        attempt.cancel();

        LOG.error("[SteamClient] FAIL: {}", steambridge.core.LogText.safe(msg));
        state = State.FAILED;
        statusMsg = TextColors.RED + msg;
        alive.set(false);
        connectLatch.countDown();
        steambridge.proxy.SteamUdpProxy.getInstance().stopClient(this);
        runtime().clearActiveClient(this);

        if (connectionHandle != 0) {
            runtime().unregisterLoopback(connectionHandle);
            runtime().closeConnection(connectionHandle, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, msg);
        }

        connectionHandle = 0;

        clearMinecraftConnection();
        showFailure(msg);
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
        return value.replace('\r', '\n')
                .replace("\n\n", "\n")
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static class TextColors {
        static final String RED = "\u00a7c";
    }

    protected final boolean isAttemptActive() {
        return alive.get() && !attempt.isCancelled() && runtime().getActiveClient() == this;
    }

    public void onLocalBridgeClosed(int connection, String reason) {
        if (connection == connectionHandle && isAttemptActive())
            fail("Steam transport closed: " + reason);
    }
}
