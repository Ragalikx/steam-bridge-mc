/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamAuth;
import com.codedisaster.steamworks.SteamAuthTicket;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamOffsets.SteamNetConnectionStatusChangedCallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SteamManager {

    private static final SteamManager INSTANCE = new SteamManager();

    public static SteamManager getInstance() {
        return INSTANCE;
    }

    private volatile boolean initialized = false;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static java.io.File nativeTempDir;

    private volatile SteamUser steamUser;
    private volatile SteamFriends steamFriends;
    private volatile SteamUtils steamUtils;
    private SteamID mySteamID;
    private volatile SteamSocketsApi socketsApi;

    private volatile SteamServer activeServer;
    private volatile SteamClient activeClient;

    private volatile Thread callbackThread;
    private volatile Thread receiveThread;

    private final Map<Integer, SteamConnectionStatus> statusByConnection = new ConcurrentHashMap<>();
    private final Map<Long, Integer> connectionBySteamId = new ConcurrentHashMap<>();
    /** LoopbackBridges registered for TCP loopback dispatch (Compatibility mode). */
    private final Map<Integer, LoopbackBridge> loopbackByConnection = new ConcurrentHashMap<>();

    /**
     * Connections identified as loopback / same-machine (ping < {@link #LOOPBACK_PING_THRESHOLD_MS}).
     * The receive thread polls these with a sub-millisecond interval (vs 1 ms for remote links).
     */
    private final java.util.Set<Integer> loopbackConnections = ConcurrentHashMap.newKeySet();

    // Lets the receive thread block (no CPU spin) while there are zero connections, and wake up
    // the instant the first connection is registered. See startReceiveThread().
    private final java.util.concurrent.locks.ReentrantLock receiveLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition hasConnections = receiveLock.newCondition();

    /**
     * Connections whose ping is below this threshold (in milliseconds) are treated as loopback.
     * For same-machine P2P the measured ping is typically 0-2 ms.
     */
    static final int LOOPBACK_PING_THRESHOLD_MS = 5;

    private SteamManager() {}

    public boolean init() {
        if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            return true;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");
        boolean loaded = com.codedisaster.steamworks.SteamAPI.loadLibraries(new com.codedisaster.steamworks.SteamLibraryLoader() {
            @Override
            public boolean loadLibrary(String libraryName) {
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    boolean is64 = System.getProperty("os.arch").contains("64");
                    String fileName;
                    if (os.contains("win")) {
                        fileName = libraryName + (is64 ? "64.dll" : ".dll");
                    } else if (os.contains("mac")) {
                        fileName = "lib" + libraryName + ".dylib";
                    } else {
                        fileName = "lib" + libraryName + ".so";
                    }

                    java.io.InputStream is = getClass().getResourceAsStream("/" + fileName);
                    if (is == null) {
                        SteamBridgeMod.LOG.error("Could not find {} in classpath", fileName);
                        return false;
                    }
                    if (nativeTempDir == null) {
                        nativeTempDir = java.nio.file.Files.createTempDirectory("steambridge_native").toFile();
                        nativeTempDir.deleteOnExit();
                    }
                    java.io.File temp = new java.io.File(nativeTempDir, fileName);
                    temp.deleteOnExit();
                    java.nio.file.Files.copy(is, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                    System.load(temp.getAbsolutePath());
                    return true;
                } catch (Throwable t) {
                    SteamBridgeMod.LOG.error("Failed to load generic library {}", libraryName, t);
                    return false;
                }
            }
        });
        if (!loaded) {
            SteamBridgeMod.LOG.error("[SteamManager] Failed to load Steam native libraries.");
            return false;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.init()...");
        try {
            if (!SteamAPI.init()) {
                SteamBridgeMod.LOG.error(
                        "[SteamManager] SteamAPI.init() returned false. Check Steam and steam_appid.txt."
                );
                return false;
            }
        } catch (SteamException e) {
            SteamBridgeMod.LOG.error("[SteamManager] SteamAPI.init() threw: {}", e.getMessage());
            return false;
        }

        try {
            // Fail fast if the bundled Steam SDK's SteamNetworkingMessage_t layout no longer
            // matches our hardcoded offsets - otherwise the raw pointer reads/writes in
            // SteamSocketsApi would silently corrupt native memory. Throws on mismatch; caught below.
            SteamOffsets.validateLayout();

            steamUser = new SteamUser(new SteamUserCallbackAdapter());
            steamFriends = new SteamFriends(new SteamFriendsCallbackAdapter());
            steamUtils = new SteamUtils(new SteamUtilsCallbackAdapter());
            mySteamID = steamUser.getSteamID();
            socketsApi = SteamSocketsApi.load();
            socketsApi.installConnectionStatusCallback(this::onConnectionStatusChanged);
            // IMPORTANT: configureForGameTraffic() MUST be called BEFORE initRelayNetworkAccess().
            // Steam applies some network configs (NagleTime, send buffer, P2P_Transport_ICE_Enable)
            // only before the relay network initialises. Calling them after means they are silently
            // ignored for the very first connection.
            socketsApi.configureForGameTraffic(steambridge.SteamBridgeConfig.allowWithoutAuth);
            socketsApi.initRelayNetworkAccess();
        } catch (Throwable t) {
            SteamBridgeMod.LOG.error("[SteamManager] Failed to initialize SteamNetworkingSockets: {}", t.getMessage(), t);
            if (steamUser != null) {
                steamUser.dispose();
                steamUser = null;
            }
            if (steamFriends != null) {
                steamFriends.dispose();
                steamFriends = null;
            }
            if (steamUtils != null) {
                steamUtils.dispose();
                steamUtils = null;
            }
            SteamAPI.shutdown();
            return false;
        }

        initialized = true;
        running.set(true);
        startCallbackThread();
        startReceiveThread();

        SteamBridgeMod.LOG.info(
                "[SteamManager] Steam initialized OK. My SteamID={} transport=ISteamNetworkingSockets",
                SteamNativeHandle.getNativeHandle(mySteamID)
        );
        return true;
    }

    public void shutdown() {
        if (!initialized) {
            return;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Shutting down...");
        running.set(false);
        signalReceiveWake(); // unblock the receive thread if it is parked waiting for connections

        // Wait for both background threads to actually exit their loop before freeing any native
        // Steam resources below. Without this, a thread can still be inside a native JNA call
        // (e.g. SteamAPI.runCallbacks()) when SteamAPI.shutdown() frees the SDK underneath it.
        joinBackgroundThread(callbackThread);
        joinBackgroundThread(receiveThread);
        callbackThread = null;
        receiveThread = null;

        SteamServer server = activeServer;
        if (server != null) {
            server.stop();
        }

        SteamClient client = activeClient;
        if (client != null) {
            client.disconnect();
        }

        statusByConnection.clear();
        connectionBySteamId.clear();
        loopbackByConnection.clear();

        if (socketsApi != null) {
            socketsApi.dispose();
            socketsApi = null;
        }

        if (steamUser != null) {
            steamUser.dispose();
            steamUser = null;
        }
        if (steamFriends != null) {
            steamFriends.dispose();
            steamFriends = null;
        }
        if (steamUtils != null) {
            steamUtils.dispose();
            steamUtils = null;
        }

        SteamAPI.shutdown();
        initialized = false;
        SteamBridgeMod.LOG.info("[SteamManager] Shutdown complete.");
    }

    /**
     * Shuts down and re-initializes Steam. Used by the "resync Steam" button in GUI.
     *
     * @return {@code true} if re-initialization succeeded
     */
    public boolean reinit() {
        SteamBridgeMod.LOG.info("[SteamManager] reinit() requested.");
        shutdown();
        return init();
    }

    private void startCallbackThread() {
        callbackThread = new Thread(() -> {
            SteamBridgeMod.LOG.info("[SteamManager] Callback thread started.");
            while (running.get()) {
                try {
                    SteamAPI.runCallbacks();
                    SteamSocketsApi api = socketsApi;
                    if (api != null) {
                        api.runCallbacks();
                    }
                    // 8 ms: callbacks only carry connection-state changes (rare during gameplay),
                    // so a faster poll buys nothing steady-state and just adds wakeups. The few ms
                    // of extra reaction time only matter during the brief connect handshake.
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamManager] Callback loop error: {}", e.getMessage(), e);
                }
            }
            SteamBridgeMod.LOG.info("[SteamManager] Callback thread stopped.");
        }, "SteamBridge-Callbacks");
        callbackThread.setDaemon(true);
        callbackThread.start();
    }

    private void startReceiveThread() {
        receiveThread = new Thread(() -> {
            SteamBridgeMod.LOG.info("[SteamManager] Receive thread started.");
            while (running.get()) {
                try {
                    // While there are no connections, block instead of spinning. rememberStatus()
                    // (and shutdown) signal this condition, so the thread wakes the instant work
                    // appears. The 1 s timeout is just a safety net against a missed signal.
                    if (statusByConnection.isEmpty()) {
                        receiveLock.lock();
                        try {
                            if (statusByConnection.isEmpty() && running.get()) {
                                hasConnections.await(1, java.util.concurrent.TimeUnit.SECONDS);
                            }
                        } finally {
                            receiveLock.unlock();
                        }
                        continue;
                    }

                    boolean didWork = false;
                    // Iterate the keySet directly: ConcurrentHashMap iteration is thread-safe and
                    // avoids allocating a fresh Integer[] on every pass of the loop.
                    for (Integer connection : statusByConnection.keySet()) {
                        // Drain until the queue is fully empty
                        while (drainConnection(connection)) {
                            didWork = true;
                        }
                    }

                    if (!didWork) {
                        // Nothing this pass: park briefly before polling again. During an active
                        // packet stream didWork stays true and this branch is never reached, so it
                        // only governs how fast the *start* of a new burst is noticed after a gap.
                        // 1 ms is imperceptible vs Minecraft's 50 ms tick and keeps CPU near idle;
                        // a tighter poll just burns a core with JNA calls (causes client stutter).
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamManager] Receive loop error: {}", e.getMessage(), e);
                }
            }
            SteamBridgeMod.LOG.info("[SteamManager] Receive thread stopped.");
        }, "SteamBridge-Receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /** Wakes the receive thread if it is parked waiting for the first connection. */
    private void signalReceiveWake() {
        receiveLock.lock();
        try {
            hasConnections.signalAll();
        } finally {
            receiveLock.unlock();
        }
    }

    /**
     * Blocks until {@code thread} exits its loop, up to a bounded timeout. Skipped if called from
     * the thread itself (the rare onSteamShutdown fallback path below) to avoid a self-join deadlock.
     */
    private void joinBackgroundThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(3000);
            if (thread.isAlive()) {
                SteamBridgeMod.LOG.warn("[SteamManager] {} did not stop within 3s of shutdown.", thread.getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean drainConnection(int connection) {
        SteamSocketsApi api = socketsApi;
        if (api == null || connection == 0) return false;

        // Receive up to 64 messages per JNA call instead of one
        SteamSocketsApi.ReceivedMessage[] batch = api.receiveMessages(connection);
        if (batch == null) return false;

        // Fast path: every message in a batch belongs to this one connection, hence the same
        // loopback bridge. Hand the whole batch over in a single event-loop hop with one flush
        // instead of one execute()+flush per message - the gameplay hot path (chunk streaming).
        LoopbackBridge loopback = loopbackByConnection.get(connection);
        if (loopback != null) {
            loopback.deliverBatchFromSteam(batch);
            return true;
        }

        // Fallback (non-loopback owners). remoteSteamID is fixed for a connection's lifetime,
        // so read it from the cached status rather than a live JNA snapshot on every batch.
        SteamConnectionStatus cached = statusByConnection.get(connection);
        long remoteSteamID = cached != null ? cached.getSteamID() : 0L;
        for (SteamSocketsApi.ReceivedMessage message : batch) {
            if (message != null) {
                dispatch(connection, remoteSteamID, message.getData());
            }
        }
        return true;
    }

    private void dispatch(int connection, long remoteSteamID, byte[] data) {
        // Loopback connections are delivered in batch by drainConnection() before reaching
        // here, so this path only handles non-loopback owners (legacy / safety net).
        SteamServer server = activeServer;
        if (server != null && server.ownsConnection(connection)) {
            server.onMessageReceived(connection, remoteSteamID, data);
            return;
        }

        SteamClient client = activeClient;
        if (client != null && client.ownsConnection(connection, remoteSteamID)) {
            client.onMessageReceived(connection, data);
            return;
        }

        SteamBridgeMod.LOG.warn(
                "[SteamManager] Received {} byte(s) for conn={} steamID={} but no owner was found.",
                data.length, connection, remoteSteamID
        );
    }

    private void onConnectionStatusChanged(SteamNetConnectionStatusChangedCallback event) {
        SteamSocketsApi api = socketsApi;
        if (api == null) {
            return;
        }

        int connection = event.m_hConn;
        long remoteSteamID = (long)(event.m_info.m_identityRemote.m_data[0] & 0xFF) |
                             ((long)(event.m_info.m_identityRemote.m_data[1] & 0xFF) << 8) |
                             ((long)(event.m_info.m_identityRemote.m_data[2] & 0xFF) << 16) |
                             ((long)(event.m_info.m_identityRemote.m_data[3] & 0xFF) << 24) |
                             ((long)(event.m_info.m_identityRemote.m_data[4] & 0xFF) << 32) |
                             ((long)(event.m_info.m_identityRemote.m_data[5] & 0xFF) << 40) |
                             ((long)(event.m_info.m_identityRemote.m_data[6] & 0xFF) << 48) |
                             ((long)(event.m_info.m_identityRemote.m_data[7] & 0xFF) << 56);
        SteamConnectionStatus status = getConnectionStatusByHandle(connection);

        if (!status.hasSessionState()) {
            status = buildFallbackStatus(connection, remoteSteamID, event);
        }
        rememberStatus(status);

        // --- Loopback detection ----------------------------------------------
        // When STATE_CONNECTED is reached and ping is very low (< 5 ms) the link is a
        // same-machine / same-LAN link. Enable fast-path (no packet coalescing) and
        // apply relaxed per-connection Steam configs (no rate caps, smaller buffer).
        if (status.getState() == SteamSocketsApi.STATE_CONNECTED
                && !loopbackConnections.contains(connection)
                && status.getPingMs() >= 0
                && status.getPingMs() < LOOPBACK_PING_THRESHOLD_MS) {

            loopbackConnections.add(connection);
            SteamBridgeMod.LOG.info(
                "[SteamManager] Loopback connection detected: conn={} steamID={} ping={}ms - enabling fast-path",
                connection, remoteSteamID, status.getPingMs());

            SteamSocketsApi api2 = socketsApi;
            if (api2 != null) {
                api2.applyLoopbackConnectionConfig(connection);
            }
        }

        boolean handled = false;

        SteamServer server = activeServer;
        if (server != null && (server.ownsListenSocket(event.m_info.m_hListenSocket) || server.ownsConnection(connection))) {
            server.onConnectionStatusChanged(connection, remoteSteamID, status, event.m_eOldState);
            handled = true;
        }

        SteamClient client = activeClient;
        if (client != null && client.ownsConnection(connection, remoteSteamID)) {
            client.onConnectionStatusChanged(connection, remoteSteamID, status, event.m_eOldState);
            handled = true;
        }

        if (!handled) {
            if (event.m_info.m_hListenSocket != 0 && server == null) {
                closeConnection(connection, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, "Host is not running");
            }
        }

        // The peer-side close path: drop bookkeeping once the owners above have seen the
        // terminal event. Locally-closed connections are handled in closeConnection().
        if (SteamSocketsApi.isTerminalState(status.getState())) {
            forgetConnection(connection);
        }
    }

    private SteamConnectionStatus buildFallbackStatus(
            int connection,
            long remoteSteamID,
            SteamNetConnectionStatusChangedCallback event
    ) {
        boolean connected = SteamSocketsApi.isConnectedState(event.m_info.m_eState);
        boolean connecting = SteamSocketsApi.isConnectingState(event.m_info.m_eState);
        boolean usingRelay = (event.m_info.m_nFlags & SteamSocketsApi.INFO_FLAG_RELAYED) != 0
                || event.m_info.m_idPOPRelay != 0;

        return new SteamConnectionStatus(
                remoteSteamID,
                connection,
                true,
                connected,
                connecting,
                usingRelay,
                event.m_info.m_eState,
                event.m_info.m_eEndReason,
                "",
                0,
                0,
                -1,
                event.m_info.m_idPOPRemote,
                event.m_info.m_idPOPRelay,
                0f,
                0f,
                0L,
                ""
        );
    }

    /**
     * Drops all bookkeeping for a connection that is gone (closed locally or reached a
     * terminal state). Without this, {@link #statusByConnection} grows for the lifetime of
     * the session and the receive thread keeps polling dead handles every millisecond.
     */
    private void forgetConnection(int connection) {
        if (connection == 0) {
            return;
        }
        loopbackConnections.remove(connection);
        loopbackByConnection.remove(connection);
        SteamConnectionStatus status = statusByConnection.remove(connection);
        if (status != null && status.getSteamID() != 0L) {
            connectionBySteamId.remove(status.getSteamID(), connection);
        }
    }

    private void rememberStatus(SteamConnectionStatus status) {
        if (status == null || status.getConnectionHandle() == 0) {
            return;
        }

        SteamConnectionStatus previous = statusByConnection.put(status.getConnectionHandle(), status);
        if (previous == null) {
            // First time this connection is seen -> wake the (possibly parked) receive thread.
            signalReceiveWake();
        }
        if (status.getSteamID() != 0L) {
            connectionBySteamId.put(status.getSteamID(), status.getConnectionHandle());
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public SteamFriends getFriends() {
        return steamFriends;
    }

    public SteamUtils getUtils() {
        return steamUtils;
    }

    public boolean isFriend(long remoteSteamID) {
        SteamFriends friends = steamFriends;
        if (friends == null || remoteSteamID == 0L) {
            return false;
        }

        try {
            return friends.getFriendRelationship(SteamID.createFromNativeHandle(remoteSteamID))
                == SteamFriends.FriendRelationship.Friend;
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn(
                "[SteamManager] Failed to resolve friend relationship for {}: {}",
                remoteSteamID, e.getMessage()
            );
            return false;
        }
    }

    public void requestUserInformation(long remoteSteamID) {
        SteamFriends friends = steamFriends;
        if (friends == null || remoteSteamID == 0L) {
            return;
        }

        try {
            friends.requestUserInformation(SteamID.createFromNativeHandle(remoteSteamID), false);
        } catch (Exception e) {
            // Ignored
        }
    }

    public SteamConnectionStatus getConnectionStatus(SteamID remote) {
        if (remote == null) {
            return SteamConnectionStatus.unavailable(0L, 0);
        }
        return getConnectionStatus(SteamNativeHandle.getNativeHandle(remote));
    }

    public SteamConnectionStatus getConnectionStatus(long remoteSteamID) {
        Integer conn = connectionBySteamId.get(remoteSteamID);
        return conn != null ? getConnectionStatusByHandle(conn) : SteamConnectionStatus.unavailable(remoteSteamID, 0);
    }

    public boolean acceptConnection(int connection) {
        SteamSocketsApi api = socketsApi;
        return api != null && api.acceptConnection(connection);
    }

    public int sendMessageFromByteBuf(int connection, io.netty.buffer.ByteBuf data, int len) {
        SteamSocketsApi api = socketsApi;
        return api != null ? api.sendMessageFromByteBuf(connection, data, len, SteamSocketsApi.SEND_RELIABLE) : 0;
    }

    public void registerLoopback(int connection, LoopbackBridge bridge) {
        if (connection != 0 && bridge != null) {
            loopbackByConnection.put(connection, bridge);
        }
    }

    public void unregisterLoopback(int connection) {
        if (connection != 0) {
            loopbackByConnection.remove(connection);
        }
    }

    public SteamConnectionStatus getConnectionStatusByHandle(int connection) {
        SteamSocketsApi api = socketsApi;
        if (!initialized || api == null || connection == 0) {
            return SteamConnectionStatus.unavailable(0L, connection);
        }

        SteamConnectionStatus status = api.snapshotConnection(connection);
        if (status.hasSessionState()) {
            rememberStatus(status);
            return status;
        }

        SteamConnectionStatus cached = statusByConnection.get(connection);
        return cached != null ? cached : SteamConnectionStatus.unavailable(0L, connection);
    }

    public void applyTransportMode(SteamServer.TransportMode mode) {
        SteamSocketsApi api = socketsApi;
        if (api != null) {
            api.applyTransportMode(mode);
        }
    }

    public int createListenSocketP2P(int virtualPort) {
        SteamSocketsApi api = socketsApi;
        return api != null ? api.createListenSocketP2P(virtualPort) : 0;
    }

    public boolean closeListenSocket(int listenSocket) {
        SteamSocketsApi api = socketsApi;
        return api != null && api.closeListenSocket(listenSocket);
    }

    public int connectP2P(SteamID remote, int virtualPort) {
        long remoteSteamID = SteamNativeHandle.getNativeHandle(remote);
        return connectP2P(remoteSteamID, virtualPort);
    }

    public int connectP2P(long remoteSteamID, int virtualPort) {
        SteamSocketsApi api = socketsApi;
        if (api == null) {
            return 0;
        }

        int connection = api.connectP2P(remoteSteamID, virtualPort);
        if (connection != 0) {
            rememberStatus(SteamConnectionStatus.unavailable(remoteSteamID, connection));
        }
        return connection;
    }

    public boolean closeConnection(int connection, int reason, String debug) {
        SteamSocketsApi api = socketsApi;
        boolean closed = api != null && api.closeConnection(connection, reason, debug, false);
        // Steam does not fire a status callback for locally-closed connections,
        // so bookkeeping must be dropped here.
        forgetConnection(connection);
        return closed;
    }

    /** @return {@code true} if a loopback bridge is currently registered for this connection. */
    public boolean isLoopbackRegistered(int connection) {
        return connection != 0 && loopbackByConnection.containsKey(connection);
    }

    public SteamServer getActiveServer() {
        return activeServer;
    }

    public SteamClient getActiveClient() {
        return activeClient;
    }

    public void setActiveServer(SteamServer server) {
        SteamBridgeMod.LOG.info("[SteamManager] setActiveServer: {}", server != null ? "SET" : "CLEAR");
        activeServer = server;
    }

    public void setActiveClient(SteamClient client) {
        SteamBridgeMod.LOG.info("[SteamManager] setActiveClient: {}", client != null ? "SET" : "CLEAR");
        activeClient = client;
    }


    private static class SteamUserCallbackAdapter implements SteamUserCallback {
        @Override
        public void onAuthSessionTicket(SteamAuthTicket authTicket, SteamResult result) {}

        @Override
        public void onValidateAuthTicket(SteamID steamID, SteamAuth.AuthSessionResponse response, SteamID ownerSteamID) {}

        @Override
        public void onMicroTxnAuthorization(int appID, long orderID, boolean authorized) {}

        @Override
        public void onEncryptedAppTicket(SteamResult result) {}
    }

    private static class SteamFriendsCallbackAdapter implements SteamFriendsCallback {
        @Override
        public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
            SteamSocial.ProfileCache.get().invalidate(SteamNativeHandle.getNativeHandle(steamID));
        }

        @Override
        public void onGameOverlayActivated(boolean active, boolean userInitiated, int appID) {}

        @Override
        public void onGameLobbyJoinRequested(SteamID steamIDLobby, SteamID steamIDFriend) {}

        @Override
        public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
            SteamSocial.ProfileCache.get().invalidateAvatar(SteamNativeHandle.getNativeHandle(steamID));
        }

        @Override
        public void onFriendRichPresenceUpdate(SteamID steamIDFriend, int appID) {}

        @Override
        public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {}

        @Override
        public void onGameServerChangeRequested(String server, String password) {}
    }

    /**
     * Called when Steam (Spacewar) exits while the game is running.
     * Stops background threads and schedules a graceful world disconnect on the MC main thread
     * so the game never crashes with a raw JNA exception.
     */
    public void handleSteamShutdown() {
        SteamBridgeMod.LOG.warn("[SteamManager] Steam shutdown signal received - scheduling graceful disconnect.");
        // Stop background threads immediately; they will finish their current iteration and exit.
        running.set(false);
        signalReceiveWake();

        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                try {
                    boolean wasClient = activeClient != null;
                    boolean wasHost   = activeServer != null && activeServer.isRunning();

                    // Tear down active Steam connections first
                    SteamClient client = activeClient;
                    if (client != null) client.disconnect();
                    SteamServer server = activeServer;
                    if (server != null) server.stop();

                    if (wasClient && mc.world != null) {
                        // Was a client inside a Steam-hosted world - kick to main menu with a friendly screen.
                        mc.loadWorld(null);
                        mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(
                            new net.minecraft.client.gui.GuiMainMenu(),
                            "disconnect.lost",
                            new net.minecraft.util.text.TextComponentTranslation("steambridge.error.steam_shutdown")
                        ));
                    } else if (wasHost && mc.player != null) {
                        // Was the host - Steam bridge died but the local world keeps running.
                        // Just warn the host in chat; they are NOT kicked.
                        mc.player.sendMessage(
                            new net.minecraft.util.text.TextComponentTranslation("steambridge.error.host_steam_shutdown")
                                .setStyle(new net.minecraft.util.text.Style()
                                    .setColor(net.minecraft.util.text.TextFormatting.RED))
                        );
                    }
                } catch (Exception e) {
                    SteamBridgeMod.LOG.error("[SteamManager] Error in Steam shutdown handler: {}", e.getMessage(), e);
                } finally {
                    shutdown();
                }
            });
        } catch (Exception e) {
            SteamBridgeMod.LOG.error("[SteamManager] Failed to schedule Steam shutdown handler: {}", e.getMessage(), e);
            shutdown();
        }
    }


    private static class SteamUtilsCallbackAdapter implements SteamUtilsCallback {
        @Override
        public void onSteamShutdown() {
            SteamBridgeMod.LOG.warn("[SteamManager] onSteamShutdown callback fired!");
            SteamManager.getInstance().handleSteamShutdown();
        }
    }
}

