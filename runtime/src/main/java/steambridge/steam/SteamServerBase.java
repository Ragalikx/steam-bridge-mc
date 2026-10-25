/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class SteamServerBase implements SteamGameServer {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    protected abstract SteamRuntime<?, ?> runtime();

    protected abstract SteamBanStore bans();

    protected abstract String displayName(long steamId);

    protected abstract void executeOnClient(Runnable task);

    protected abstract boolean integratedServerAvailable();

    protected abstract boolean createLoopbackBridge(
            int handle, long steamId, int port, java.util.function.IntConsumer bound);

    protected boolean voiceSupported() {
        return true;
    }

    public enum AccessPolicy {
        EVERYONE,
        FRIENDS_ONLY
    }

    public enum TransportMode {
        AUTO,
        P2P_ONLY,
        RELAY_ONLY
    }

    public static final class PlayerSnapshot {
        private final long steamId;
        private final String steamName;
        private final String minecraftName;
        private final int connectionHandle;
        private final int localProxyPort;
        private final long connectedAtMs;
        private final long kickRemainingMs;
        private final SteamConnectionStatus connectionStatus;

        private PlayerSnapshot(
                long steamId,
                String steamName,
                String minecraftName,
                int connectionHandle,
                int localProxyPort,
                long connectedAtMs,
                long kickRemainingMs,
                SteamConnectionStatus connectionStatus) {
            this.steamId = steamId;
            this.steamName = steamName;
            this.minecraftName = minecraftName;
            this.connectionHandle = connectionHandle;
            this.localProxyPort = localProxyPort;
            this.connectedAtMs = connectedAtMs;
            this.kickRemainingMs = kickRemainingMs;
            this.connectionStatus = connectionStatus;
        }

        public long getSteamId() {
            return steamId;
        }

        public String getSteamName() {
            return steamName;
        }

        public String getMinecraftName() {
            return minecraftName;
        }

        public int getConnectionHandle() {
            return connectionHandle;
        }

        public int getLocalProxyPort() {
            return localProxyPort;
        }

        public long getConnectedAtMs() {
            return connectedAtMs;
        }

        public long getKickRemainingMs() {
            return kickRemainingMs;
        }

        public SteamConnectionStatus getConnectionStatus() {
            return connectionStatus;
        }
    }

    private static final class PlayerSession {
        private final long steamId;
        private final long connectedAtMs = System.currentTimeMillis();

        private volatile int connectionHandle;
        private volatile int localProxyPort = -1;
        private volatile String minecraftName = "";

        private PlayerSession(long steamId, int connectionHandle) {
            this.steamId = steamId;
            this.connectionHandle = connectionHandle;
        }
    }

    private static final class AccessDecision {
        private final boolean allowed;
        private final String reason;

        private AccessDecision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    private static final long KICK_BLOCK_DURATION_MS = 60_000L;

    private volatile int mcPort = 25565;
    private volatile boolean running = false;
    private volatile int listenSocket = 0;
    private volatile AccessPolicy accessPolicy = AccessPolicy.EVERYONE;
    private volatile TransportMode transportMode = TransportMode.AUTO;
    private volatile String worldKey = "__default_world__";
    private volatile String worldDisplayName = "World";

    private final ConcurrentMap<Integer, Long> steamIdByConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> connectionBySteamId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PlayerSession> sessionsBySteamId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Long> steamIdByProxyPort = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Integer> proxyPortByConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> kickBlockedUntilBySteamId = new ConcurrentHashMap<>();
    private final Set<Integer> acceptedConnections = ConcurrentHashMap.newKeySet();

    protected SteamServerBase() {}

    protected SteamServerBase(AccessPolicy accessPolicy, String worldKey, String worldDisplayName) {
        this.accessPolicy = accessPolicy != null ? accessPolicy : AccessPolicy.EVERYONE;
        setWorldIdentity(worldKey, worldDisplayName);
    }

    public void setMcPort(int port) {
        mcPort = port;
        LOG.info("[SteamServer] MC port updated to {}", port);
        steambridge.proxy.SteamUdpProxy.getInstance().setHostGamePort(port);
    }

    public int getMcPort() {
        return mcPort;
    }

    public void setAccessPolicy(AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy != null ? accessPolicy : AccessPolicy.EVERYONE;
    }

    public AccessPolicy getAccessPolicy() {
        return accessPolicy;
    }

    public void setTransportMode(TransportMode mode) {
        this.transportMode = mode != null ? mode : TransportMode.AUTO;
    }

    public TransportMode getTransportMode() {
        return transportMode;
    }

    public void setWorldIdentity(String worldKey, String worldDisplayName) {
        this.worldKey = sanitizeWorldValue(worldKey, "__default_world__");
        this.worldDisplayName = sanitizeWorldValue(worldDisplayName, "World");
    }

    public String getWorldKey() {
        return worldKey;
    }

    public String getWorldDisplayName() {
        return worldDisplayName;
    }

    protected synchronized boolean startHost() {
        if (running) {
            LOG.warn("[SteamServer] start() while already running - ignoring.");
            return true;
        }

        SteamGameServer previous = runtime().getActiveServer();
        if (previous != null && previous != this && previous.isRunning()) previous.stop();
        if (!runtime().claimActiveServer(this)) {
            LOG.warn("[SteamServer] Another host is starting.");
            return false;
        }
        running = true;
        steamIdByConnection.clear();
        connectionBySteamId.clear();
        sessionsBySteamId.clear();
        steamIdByProxyPort.clear();
        proxyPortByConnection.clear();
        kickBlockedUntilBySteamId.clear();
        acceptedConnections.clear();

        runtime().applyTransportMode(transportMode);
        listenSocket =
                runtime()
                        .createListenSocketP2P(
                                steambridge.SteamBridgeConfig.settings().virtualPort);
        if (listenSocket == 0) {
            running = false;
            runtime().clearActiveServer(this);
            LOG.error("[SteamServer] Failed to create listen socket for Steam relay.");
            return false;
        }

        if (voiceSupported() && steambridge.SteamBridgeConfig.settings().interceptUdp) {
            steambridge.proxy.SteamUdpProxy.getInstance().startServer();
        }
        LOG.info(
                "[SteamServer] Started. listenSocket={} SteamChannel (direct, no TCP) world={}"
                        + " access={}",
                listenSocket,
                worldKey,
                accessPolicy);
        LOG.info("Server started. World=" + worldKey + " access=" + accessPolicy);
        return true;
    }

    public synchronized void stop() {
        if (!running) {
            LOG.warn("[SteamServer] stop() while already stopped - ignoring.");
            return;
        }

        running = false;
        if (voiceSupported()) steambridge.proxy.SteamUdpProxy.getInstance().stopServer();
        runtime().clearActiveServer(this);

        for (Map.Entry<Integer, Long> entry : steamIdByConnection.entrySet()) {
            runtime()
                    .closeConnection(
                            entry.getKey(), SteamSocketsApi.APP_CLOSE_NORMAL, "Host stopped");
        }

        if (listenSocket != 0) {
            runtime().closeListenSocket(listenSocket);
            listenSocket = 0;
        }

        steamIdByConnection.clear();
        connectionBySteamId.clear();
        sessionsBySteamId.clear();
        steamIdByProxyPort.clear();
        proxyPortByConnection.clear();
        kickBlockedUntilBySteamId.clear();
        acceptedConnections.clear();
        LOG.info("[SteamServer] Stopped.");
    }

    public void onConnectionStatusChanged(
            int connection, long steamID, SteamConnectionStatus status, int oldState) {
        if (!running) {
            runtime()
                    .closeConnection(
                            connection,
                            SteamSocketsApi.APP_CLOSE_LOCAL_ERROR,
                            "Host is not running");
            return;
        }

        if (status.isTerminal()) {
            cleanupConnection(connection, steamID);
            return;
        }
        rememberConnection(connection, steamID);
        runtime().requestUserInformation(steamID);

        LOG.info(
                "[SteamServer] Connection callback: conn={} steamID={} state={} route={}",
                connection,
                steamID,
                status.describeState(),
                status.describeRoute());

        AccessDecision decision = evaluateAccess(steamID);
        if (!decision.allowed) {
            LOG.info(
                    "[SteamServer] Rejecting conn={} steamID={} reason={}",
                    connection,
                    steamID,
                    decision.reason);
            closeAndCleanup(connection, steamID, decision.reason);
            return;
        }

        if (status.getState() == SteamSocketsApi.STATE_CONNECTING
                || status.getState() == SteamSocketsApi.STATE_FINDING_ROUTE) {
            if (acceptedConnections.add(connection)) {
                boolean accepted = runtime().acceptConnection(connection);
                LOG.info(
                        "[SteamServer] Accept incoming conn={} steamID={} accepted={}",
                        connection,
                        steamID,
                        accepted);
                String name = displayName(steamID);
                LOG.info("Incoming: {} (steamID={})", steambridge.core.LogText.safe(name), steamID);
                if (!accepted) {
                    closeAndCleanup(connection, steamID, "AcceptConnection failed");
                }
            }
            return;
        }

        if (status.getState() == SteamSocketsApi.STATE_CONNECTED) {
            createBridge(connection, steamID);
            return;
        }

        if (status.isTerminal()) {
            LOG.info(
                    "[SteamServer] Terminal state for conn={} steamID={} err={}",
                    connection,
                    steamID,
                    status.getLastError());
            String name = displayName(steamID);
            String err = status.getLastError();
            LOG.info(
                    "Disconnected: {} (steamID={}){}",
                    steambridge.core.LogText.safe(name),
                    steamID,
                    err.isEmpty() ? "" : " - " + steambridge.core.LogText.safe(err));
            cleanupConnection(connection, steamID);
        }
    }

    public void onMessageReceived(int connection, long steamID, byte[] data) {
        if (!running) {
            return;
        }

        if (isKickBlocked(steamID)) {
            return;
        }

        LOG.warn(
                "[SteamServer] Received {} byte(s) for conn={} steamID={} but no channel is"
                        + " registered.",
                data.length,
                connection,
                steamID);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPlayerCount() {
        return sessionsBySteamId.size();
    }

    public List<Long> getConnectedSteamIDs() {
        return new ArrayList<>(connectionBySteamId.keySet());
    }

    public SteamConnectionStatus getConnectionStatus(long steamID) {
        return runtime().getConnectionStatus(steamID);
    }

    public List<PlayerSnapshot> getPlayerSnapshots() {
        expireKickBlocks();

        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (PlayerSession session : sessionsBySteamId.values()) {
            SteamConnectionStatus status = getConnectionStatus(session.steamId);
            long kickRemaining = getKickRemainingMs(session.steamId);
            snapshots.add(
                    new PlayerSnapshot(
                            session.steamId,
                            displayName(session.steamId),
                            session.minecraftName,
                            session.connectionHandle,
                            session.localProxyPort,
                            session.connectedAtMs,
                            kickRemaining,
                            status));
        }

        snapshots.sort(
                Comparator.comparing(PlayerSnapshot::getSteamName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(
                                PlayerSnapshot::getMinecraftName, String.CASE_INSENSITIVE_ORDER));
        return snapshots;
    }

    public List<SteamBanStore.Record> getBanRecords() {
        return bans().getBans(worldKey);
    }

    public boolean banPlayer(long steamId) {
        if (steamId == 0L) return false;
        PlayerSession session = sessionsBySteamId.get(steamId);
        String steamName = displayName(steamId);
        String minecraftName = session != null ? session.minecraftName : "";
        bans().ban(worldKey, steamId, steamName, minecraftName);
        disconnectSteamPeer(steamId, "steambridge.disconnect.banned");
        LOG.warn(
                "Banned: {} (steamID={}){}",
                steambridge.core.LogText.safe(steamName),
                steamId,
                minecraftName.isEmpty()
                        ? ""
                        : " mc=" + steambridge.core.LogText.safe(minecraftName));
        return true;
    }

    public boolean unbanPlayer(long steamId) {
        boolean removed = steamId != 0L && bans().unban(worldKey, steamId);
        if (removed)
            LOG.info(
                    "Unbanned: {} (steamID={})",
                    steambridge.core.LogText.safe(displayName(steamId)),
                    steamId);
        return removed;
    }

    public boolean kickPlayer(long steamId) {
        if (steamId == 0L) {
            return false;
        }
        PlayerSession session = sessionsBySteamId.get(steamId);
        String steamName = displayName(steamId);
        String minecraftName = session != null ? session.minecraftName : "";

        kickBlockedUntilBySteamId.put(steamId, System.currentTimeMillis() + KICK_BLOCK_DURATION_MS);
        disconnectSteamPeer(steamId, "steambridge.disconnect.kicked");

        LOG.info(
                "Kicked: {} (steamID={}){}",
                steambridge.core.LogText.safe(steamName),
                steamId,
                minecraftName.isEmpty()
                        ? ""
                        : " mc=" + steambridge.core.LogText.safe(minecraftName));
        return true;
    }

    public boolean ownsListenSocket(int socket) {
        return socket != 0 && socket == listenSocket;
    }

    public boolean ownsConnection(int connection) {
        return steamIdByConnection.containsKey(connection);
    }

    public int getListenSocket() {
        return listenSocket;
    }

    public boolean attachMinecraftPlayer(InetSocketAddress remoteAddress, String minecraftName) {
        if (remoteAddress == null) {
            return false;
        }

        Long steamId = steamIdByProxyPort.get(remoteAddress.getPort());
        if (steamId == null) {
            return false;
        }

        PlayerSession session = sessionsBySteamId.get(steamId);
        if (session == null) {
            return false;
        }

        session.minecraftName = minecraftName != null ? minecraftName.trim() : "";
        LOG.info(
                "[SteamServer] Attached MC identity '{}' to steamID={} via local port {}",
                steambridge.core.LogText.safe(session.minecraftName),
                steamId,
                remoteAddress.getPort());
        LOG.info(
                "Player joined: {} (Steam: {}, steamID={})",
                steambridge.core.LogText.safe(session.minecraftName),
                steambridge.core.LogText.safe(displayName(steamId)),
                steamId);
        return true;
    }

    private void createBridge(int connection, long steamID) {
        if (!isCurrentConnection(connection, steamID)) {
            runtime()
                    .closeConnection(
                            connection, SteamSocketsApi.APP_CLOSE_NORMAL, "Expired host session");
            return;
        }

        if (steamIdByConnection.containsKey(connection)
                && runtime().isLoopbackRegistered(connection)) {
            return;
        }

        executeOnClient(
                () -> {
                    if (!isCurrentConnection(connection, steamID)) {
                        runtime()
                                .closeConnection(
                                        connection,
                                        SteamSocketsApi.APP_CLOSE_NORMAL,
                                        "Expired host session");
                        return;
                    }

                    if (integratedServerAvailable()) {

                        Integer oldConn = connectionBySteamId.get(steamID);
                        if (oldConn != null && oldConn != connection) {
                            if (runtime().isLoopbackRegistered(oldConn)) {
                                LOG.info(
                                        "[SteamServer] Proactively closing stale loopback bridge"
                                                + " conn={} for reconnecting steamID={}",
                                        oldConn,
                                        steamID);
                                runtime().unregisterLoopback(oldConn);
                            }
                            runtime()
                                    .closeConnection(
                                            oldConn,
                                            SteamSocketsApi.APP_CLOSE_NORMAL,
                                            "Replaced by reconnect");
                        }

                        PlayerSession session =
                                sessionsBySteamId.computeIfAbsent(
                                        steamID, key -> new PlayerSession(steamID, connection));
                        session.connectionHandle = connection;

                        boolean success =
                                createLoopbackBridge(
                                        connection,
                                        steamID,
                                        mcPort,
                                        localPort -> {
                                            session.localProxyPort = localPort;
                                            steamIdByProxyPort.put(localPort, steamID);
                                            proxyPortByConnection.put(connection, localPort);
                                        });

                        if (!success) {
                            LOG.error(
                                    "[SteamServer] Loopback bridge creation failed for conn={} -"
                                            + " closing.",
                                    connection);
                            closeAndCleanup(connection, steamID, "Loopback bridge creation failed");
                            return;
                        }

                        if (!isCurrentConnection(connection, steamID)) {
                            runtime()
                                    .closeConnection(
                                            connection,
                                            SteamSocketsApi.APP_CLOSE_NORMAL,
                                            "Expired host session");
                            return;
                        }
                        rememberConnection(connection, steamID);
                        LOG.info(
                                "[SteamServer] Loopback bridge ready for conn={} steamID={}",
                                connection,
                                steamID);
                        return;
                    }

                    LOG.error(
                            "[SteamServer] No integrated server for conn={} steamID={} - closing"
                                    + " connection.",
                            connection,
                            steamID);
                    closeAndCleanup(connection, steamID, "No integrated server");
                });
    }

    private void rememberConnection(int connection, long steamID) {
        steamIdByConnection.put(connection, steamID);
        if (steamID == 0L) return;
        Integer previous = connectionBySteamId.put(steamID, connection);
        if (previous != null && previous != connection) {
            closeAndCleanup(previous, steamID, "Replaced by reconnect");
        }
        sessionsBySteamId.compute(
                steamID,
                (key, existing) ->
                        existing != null && existing.connectionHandle == connection
                                ? existing
                                : new PlayerSession(steamID, connection));
    }

    public Object getVoiceSessionToken(long steamId) {
        PlayerSession session = sessionsBySteamId.get(steamId);
        Integer current = connectionBySteamId.get(steamId);
        if (!running
                || session == null
                || !acceptedConnections.contains(session.connectionHandle)
                || current == null
                || current != session.connectionHandle
                || !evaluateAccess(steamId).allowed) return null;
        return session;
    }

    private void cleanupConnection(int connection, long steamID) {
        PlayerSession voiceSession = sessionsBySteamId.get(steamID);

        acceptedConnections.remove(connection);
        steamIdByConnection.remove(connection);

        Integer proxyPort = proxyPortByConnection.remove(connection);
        if (proxyPort != null) {
            steamIdByProxyPort.remove(proxyPort, steamID);
        }

        if (steamID != 0L && connectionBySteamId.remove(steamID, connection)) {
            sessionsBySteamId.computeIfPresent(
                    steamID,
                    (key, session) -> session.connectionHandle == connection ? null : session);
        }

        if (voiceSession != null && voiceSession.connectionHandle == connection)
            steambridge.proxy.SteamUdpProxy.getInstance().closeServerPeer(steamID, voiceSession);
        runtime().unregisterLoopback(connection);
    }

    private AccessDecision evaluateAccess(long steamId) {
        expireKickBlocks();
        if (bans().isBanned(worldKey, steamId)) {
            return new AccessDecision(false, "steambridge.disconnect.banned");
        }
        if (isKickBlocked(steamId)) {
            return new AccessDecision(false, "steambridge.disconnect.kicked");
        }
        if (accessPolicy == AccessPolicy.FRIENDS_ONLY && !runtime().isFriend(steamId)) {
            return new AccessDecision(false, "steambridge.disconnect.friends_only");
        }
        return new AccessDecision(true, "");
    }

    private boolean isKickBlocked(long steamId) {
        return getKickRemainingMs(steamId) > 0L;
    }

    private long getKickRemainingMs(long steamId) {
        Long until = kickBlockedUntilBySteamId.get(steamId);
        if (until == null) {
            return 0L;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            kickBlockedUntilBySteamId.remove(steamId, until);
            return 0L;
        }
        return remaining;
    }

    private void expireKickBlocks() {
        for (Map.Entry<Long, Long> entry : kickBlockedUntilBySteamId.entrySet()) {
            if (entry.getValue() <= System.currentTimeMillis()) {
                kickBlockedUntilBySteamId.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void disconnectSteamPeer(long steamId, String reason) {
        Integer connection = connectionBySteamId.get(steamId);
        if (connection != null) {
            runtime().unregisterLoopback(connection);
            runtime().closeConnection(connection, SteamSocketsApi.APP_CLOSE_REMOTE_ERROR, reason);
            cleanupConnection(connection, steamId);
        }
    }

    private void closeAndCleanup(int connection, long steamID, String reason) {
        runtime().closeConnection(connection, SteamSocketsApi.APP_CLOSE_REMOTE_ERROR, reason);
        cleanupConnection(connection, steamID);
    }

    private static String sanitizeWorldValue(String value, String fallback) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private boolean isCurrentConnection(int connection, long steamID) {
        Integer active = connectionBySteamId.get(steamID);
        return running
                && active != null
                && active == connection
                && steamIdByConnection.containsKey(connection);
    }

    public void onLocalBridgeClosed(int connection) {
        Long steamID = steamIdByConnection.get(connection);
        if (steamID != null) cleanupConnection(connection, steamID);
    }
}
