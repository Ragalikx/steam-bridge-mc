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

import steambridge.SteamBridgeMod;
import steambridge.SteamBridgeConfig;
// SteamSocial and SteamStorage are in same package - no import needed

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SteamServer {

    public enum AccessPolicy {
        EVERYONE,
        FRIENDS_ONLY
    }

    public enum TransportMode {
        AUTO,
        P2P_ONLY,
        RELAY_ONLY
    }

    /**
     * Compression method used for Steam session traffic (server -> client only).
     * <ul>
     *   <li>{@link #VANILLA} - No Steam-level compression. Minecraft's own zlib handles it as-is.</li>
     * </ul>
     * Note: a separate ZLIB mode was removed - Minecraft already compresses packets with ZLIB
     * internally, so adding a second ZLIB layer on top only wastes CPU without any gain.
     */
    public enum CompressionMode {
        /** Disables Steam transport compression; Minecraft's built-in zlib is used as-is. */
        VANILLA
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
            SteamConnectionStatus connectionStatus
        ) {
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

    private static final String MC_HOST = "127.0.0.1";
    private static final long KICK_BLOCK_MS = 10_000L;

    private volatile int mcPort = 25565;
    private volatile boolean running = false;
    private volatile int listenSocket = 0;
    private volatile AccessPolicy accessPolicy = AccessPolicy.EVERYONE;
    private volatile TransportMode transportMode = TransportMode.AUTO;
    private volatile CompressionMode compressionMode = CompressionMode.VANILLA;
    private volatile String worldKey = "__default_world__";
    private volatile String worldDisplayName = "World";

    private final ConcurrentMap<Integer, Long> steamIdByConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> connectionBySteamId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PlayerSession> sessionsBySteamId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Long> steamIdByProxyPort = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Integer> proxyPortByConnection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> kickBlockedUntilBySteamId = new ConcurrentHashMap<>();
    private final Set<Integer> acceptedConnections = ConcurrentHashMap.newKeySet();

    public SteamServer() {}

    public SteamServer(AccessPolicy accessPolicy, String worldKey, String worldDisplayName) {
        this.accessPolicy = accessPolicy != null ? accessPolicy : AccessPolicy.EVERYONE;
        setWorldIdentity(worldKey, worldDisplayName);
    }

    public void setMcPort(int port) {
        mcPort = port;
        SteamBridgeMod.LOG.info("[SteamServer] MC port updated to {}", port);
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

    public void setCompressionMode(CompressionMode mode) {
        this.compressionMode = mode != null ? mode : CompressionMode.VANILLA;
    }

    public CompressionMode getCompressionMode() {
        return compressionMode;
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

    public void start() {
        if (running) {
            SteamBridgeMod.LOG.warn("[SteamServer] start() while already running - ignoring.");
            return;
        }

        running = true;
        steamIdByConnection.clear();
        connectionBySteamId.clear();
        sessionsBySteamId.clear();
        steamIdByProxyPort.clear();
        proxyPortByConnection.clear();
        kickBlockedUntilBySteamId.clear();
        acceptedConnections.clear();

        SteamManager.getInstance().setActiveServer(this);
        // Apply transport mode before creating listen socket so Steam picks up the config.
        SteamManager.getInstance().applyTransportMode(transportMode);
        listenSocket = SteamManager.getInstance().createListenSocketP2P(steambridge.SteamBridgeConfig.virtualPort);
        if (listenSocket == 0) {
            running = false;
            SteamManager.getInstance().setActiveServer(null);
            SteamBridgeMod.LOG.error("[SteamServer] Failed to create listen socket for Steam relay.");
            return;
        }

        SteamBridgeMod.LOG.info(
            "[SteamServer] Started. listenSocket={} SteamChannel (direct, no TCP) world={} access={}",
            listenSocket, worldKey, accessPolicy
        );
        SteamBridgeMod.LOG.info("Server started. World=" + worldKey + " access=" + accessPolicy);
    }

    public void stop() {
        if (!running) {
            SteamBridgeMod.LOG.warn("[SteamServer] stop() while already stopped - ignoring.");
            return;
        }

        running = false;
        SteamManager.getInstance().setActiveServer(null);

        // Close all active connections
        for (Map.Entry<Integer, Long> entry : steamIdByConnection.entrySet()) {
            SteamManager.getInstance().closeConnection(
                entry.getKey(), SteamSocketsApi.APP_CLOSE_NORMAL, "Host stopped"
            );
        }

        if (listenSocket != 0) {
            SteamManager.getInstance().closeListenSocket(listenSocket);
            listenSocket = 0;
        }

        steamIdByConnection.clear();
        connectionBySteamId.clear();
        sessionsBySteamId.clear();
        steamIdByProxyPort.clear();
        proxyPortByConnection.clear();
        kickBlockedUntilBySteamId.clear();
        acceptedConnections.clear();
        SteamBridgeMod.LOG.info("[SteamServer] Stopped.");
    }

    public void onConnectionStatusChanged(int connection, long steamID, SteamConnectionStatus status, int oldState) {
        if (!running) {
            SteamManager.getInstance().closeConnection(
                connection, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, "Host is not running"
            );
            return;
        }

        rememberConnection(connection, steamID);
        SteamManager.getInstance().requestUserInformation(steamID);

        SteamBridgeMod.LOG.info(
            "[SteamServer] Connection callback: conn={} steamID={} state={} route={}",
            connection, steamID, status.describeState(), status.describeRoute()
        );

        AccessDecision decision = evaluateAccess(steamID);
        if (!decision.allowed) {
            SteamBridgeMod.LOG.info(
                "[SteamServer] Rejecting conn={} steamID={} reason={}",
                connection, steamID, decision.reason
            );
            closeAndCleanup(connection, steamID, decision.reason);
            return;
        }

        if (status.getState() == SteamSocketsApi.STATE_CONNECTING
            || status.getState() == SteamSocketsApi.STATE_FINDING_ROUTE) {
            if (acceptedConnections.add(connection)) {
                boolean accepted = SteamManager.getInstance().acceptConnection(connection);
                SteamBridgeMod.LOG.info(
                    "[SteamServer] Accept incoming conn={} steamID={} accepted={}",
                    connection, steamID, accepted
                );
                String name = SteamSocial.ProfileCache.get().getDisplayName(steamID);
                SteamBridgeMod.LOG.info("Incoming: {} (steamID={})", SteamBridgeMod.safeLog(name), steamID);
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
            SteamBridgeMod.LOG.info(
                "[SteamServer] Terminal state for conn={} steamID={} err={}",
                connection, steamID, status.getLastError()
            );
            String name = SteamSocial.ProfileCache.get().getDisplayName(steamID);
            String err = status.getLastError();
            SteamBridgeMod.LOG.info("Disconnected: {} (steamID={}){}", SteamBridgeMod.safeLog(name), steamID,
                    err.isEmpty() ? "" : " - " + SteamBridgeMod.safeLog(err));
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

        // Messages for SteamChannel connections are delivered directly by SteamManager.
        // This path is only reached if data arrives before the channel is registered.
        SteamBridgeMod.LOG.warn(
            "[SteamServer] Received {} byte(s) for conn={} steamID={} but no channel is registered.",
            data.length, connection, steamID
        );
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
        return SteamManager.getInstance().getConnectionStatus(steamID);
    }

    public List<PlayerSnapshot> getPlayerSnapshots() {
        expireKickBlocks();

        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (PlayerSession session : sessionsBySteamId.values()) {
            SteamConnectionStatus status = getConnectionStatus(session.steamId);
            long kickRemaining = getKickRemainingMs(session.steamId);
            snapshots.add(new PlayerSnapshot(
                session.steamId,
                SteamSocial.ProfileCache.get().getDisplayName(session.steamId),
                session.minecraftName,
                session.connectionHandle,
                session.localProxyPort,
                session.connectedAtMs,
                kickRemaining,
                status
            ));
        }

        snapshots.sort(Comparator
            .comparing(PlayerSnapshot::getSteamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PlayerSnapshot::getMinecraftName, String.CASE_INSENSITIVE_ORDER));
        return snapshots;
    }

    public List<SteamSocial.Bans.Record> getBanRecords() {
        return SteamSocial.Bans.get().getBans(worldKey);
    }

    public boolean banPlayer(long steamId) {
        if (steamId == 0L) return false;
        PlayerSession session = sessionsBySteamId.get(steamId);
        String steamName      = SteamSocial.ProfileCache.get().getDisplayName(steamId);
        String minecraftName  = session != null ? session.minecraftName : "";
        SteamSocial.Bans.get().ban(worldKey, steamId, steamName, minecraftName);
        String msg = net.minecraft.client.resources.I18n.hasKey("steambridge.disconnect.banned") ?
                     net.minecraft.client.resources.I18n.format("steambridge.disconnect.banned") : "You are banned from this world";
        disconnectSteamPeer(steamId, msg);
        SteamBridgeMod.LOG.warn("Banned: {} (steamID={}){}", SteamBridgeMod.safeLog(steamName), steamId,
                minecraftName.isEmpty() ? "" : " mc=" + SteamBridgeMod.safeLog(minecraftName));
        return true;
    }

    public boolean unbanPlayer(long steamId) {
        boolean removed = steamId != 0L && SteamSocial.Bans.get().unban(worldKey, steamId);
        if (removed) SteamBridgeMod.LOG.info("Unbanned: {} (steamID={})",
                SteamBridgeMod.safeLog(SteamSocial.ProfileCache.get().getDisplayName(steamId)), steamId);
        return removed;
    }

    public boolean kickPlayer(long steamId) {
        if (steamId == 0L) {
            return false;
        }
        PlayerSession session = sessionsBySteamId.get(steamId);
        String steamName      = SteamSocial.ProfileCache.get().getDisplayName(steamId);
        String minecraftName  = session != null ? session.minecraftName : "";

        kickBlockedUntilBySteamId.put(steamId, System.currentTimeMillis() + 300_000L); 
        String msg = net.minecraft.client.resources.I18n.hasKey("steambridge.disconnect.kicked") ? 
                     net.minecraft.client.resources.I18n.format("steambridge.disconnect.kicked") : "Host temporarily blocked this connection";
        disconnectSteamPeer(steamId, msg);

        SteamBridgeMod.LOG.info("Kicked: {} (steamID={}){}", SteamBridgeMod.safeLog(steamName), steamId,
                minecraftName.isEmpty() ? "" : " mc=" + SteamBridgeMod.safeLog(minecraftName));
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
        SteamBridgeMod.LOG.info(
            "[SteamServer] Attached MC identity '{}' to steamID={} via local port {}",
            SteamBridgeMod.safeLog(session.minecraftName), steamId, remoteAddress.getPort()
        );
        SteamBridgeMod.LOG.info("Player joined: {} (Steam: {}, steamID={})",
                SteamBridgeMod.safeLog(session.minecraftName),
                SteamBridgeMod.safeLog(SteamSocial.ProfileCache.get().getDisplayName(steamId)), steamId);
        return true;
    }

    private void createBridge(int connection, long steamID) {
        if (steamIdByConnection.containsKey(connection) && SteamManager.getInstance().isLoopbackRegistered(connection)) {
            return; // already registered
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.getIntegratedServer() != null) {
            // Reconnect guard
            Integer oldConn = connectionBySteamId.get(steamID);
            if (oldConn != null && oldConn != connection) {
                if (SteamManager.getInstance().isLoopbackRegistered(oldConn)) {
                    SteamBridgeMod.LOG.info(
                        "[SteamServer] Proactively closing stale loopback bridge conn={} for reconnecting steamID={}",
                        oldConn, steamID);
                    SteamManager.getInstance().unregisterLoopback(oldConn);
                }
                SteamManager.getInstance().closeConnection(
                    oldConn, SteamSocketsApi.APP_CLOSE_NORMAL, "Replaced by reconnect");
            }

            PlayerSession session = sessionsBySteamId.computeIfAbsent(
                    steamID, key -> new PlayerSession(steamID, connection));
            session.connectionHandle = connection;

            boolean success = SteamTransport.createServerLoopbackBridge(
                connection, steamID, mcPort,
                localPort -> {
                    session.localProxyPort = localPort;
                    steamIdByProxyPort.put(localPort, steamID);
                    proxyPortByConnection.put(connection, localPort);
                }
            );

            if (!success) {
                SteamBridgeMod.LOG.error("[SteamServer] Loopback bridge creation failed for conn={} - closing.", connection);
                closeAndCleanup(connection, steamID, "Loopback bridge creation failed");
                return;
            }

            rememberConnection(connection, steamID);
            SteamBridgeMod.LOG.info("[SteamServer] Loopback bridge ready for conn={} steamID={}", connection, steamID);
            return;
        }

        SteamBridgeMod.LOG.error(
            "[SteamServer] No integrated server for conn={} steamID={} - closing connection.", connection, steamID);
        closeAndCleanup(connection, steamID, "No integrated server");
    }

    private void rememberConnection(int connection, long steamID) {
        steamIdByConnection.put(connection, steamID);
        if (steamID != 0L) {
            sessionsBySteamId.computeIfAbsent(steamID, key -> new PlayerSession(steamID, connection)).connectionHandle = connection;
            Integer previous = connectionBySteamId.put(steamID, connection);
            if (previous != null && previous != connection) {
                steamIdByConnection.remove(previous);
                acceptedConnections.remove(previous);
            }
        }
    }

    private void cleanupConnection(int connection, long steamID) {
        acceptedConnections.remove(connection);
        steamIdByConnection.remove(connection);

        Integer proxyPort = proxyPortByConnection.remove(connection);
        if (proxyPort != null) {
            steamIdByProxyPort.remove(proxyPort, steamID);
        }

        if (steamID != 0L && connectionBySteamId.remove(steamID, connection)) {
            sessionsBySteamId.remove(steamID);
        }
    }

    private void onBridgeClosed(int connection, long steamID) {
        // Kept for potential future use; not called in SteamChannel mode.
        cleanupConnection(connection, steamID);
    }

    private AccessDecision evaluateAccess(long steamId) {
        expireKickBlocks();
        if (SteamSocial.Bans.get().isBanned(worldKey, steamId)) {
            String msg = net.minecraft.client.resources.I18n.hasKey("steambridge.disconnect.banned") ? 
                         net.minecraft.client.resources.I18n.format("steambridge.disconnect.banned") : "You are banned from this world";
            return new AccessDecision(false, msg);
        }
        if (isKickBlocked(steamId)) {
            String msg = net.minecraft.client.resources.I18n.hasKey("steambridge.disconnect.kicked") ? 
                         net.minecraft.client.resources.I18n.format("steambridge.disconnect.kicked") : "Host temporarily blocked this connection";
            return new AccessDecision(false, msg);
        }
        if (accessPolicy == AccessPolicy.FRIENDS_ONLY && !SteamManager.getInstance().isFriend(steamId)) {
            String msg = net.minecraft.client.resources.I18n.hasKey("steambridge.disconnect.friends_only") ? 
                         net.minecraft.client.resources.I18n.format("steambridge.disconnect.friends_only") : "Host allows only Steam friends";
            return new AccessDecision(false, msg);
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
            SteamManager.getInstance().unregisterLoopback(connection);
            SteamManager.getInstance().closeConnection(connection, SteamSocketsApi.APP_CLOSE_REMOTE_ERROR, reason);
            cleanupConnection(connection, steamId);
        }
    }

    private void closeAndCleanup(int connection, long steamID, String reason) {
        SteamManager.getInstance().closeConnection(
            connection,
            SteamSocketsApi.APP_CLOSE_REMOTE_ERROR,
            reason
        );
        cleanupConnection(connection, steamID);
    }

    private static String sanitizeWorldValue(String value, String fallback) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}


