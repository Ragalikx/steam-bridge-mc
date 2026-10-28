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
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;

import steambridge.core.CloseReason;
import steambridge.core.ConnectionRegistry;
import steambridge.core.DiagnosticReport;
import steambridge.core.InboundMessage;
import steambridge.core.LogLimiter;
import steambridge.core.LogText;
import steambridge.core.NativeCallGate;
import steambridge.core.ProblemLog;
import steambridge.core.ReceiveBackoff;
import steambridge.core.SendQueueState;
import steambridge.steam.SteamOffsets.SteamNetConnectionStatusChangedCallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SteamRuntime<C extends SteamGameClient, S extends SteamGameServer>
        implements LoopbackBridge.Driver, steambridge.voice.VoiceNativeDriver {
    private final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    protected static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    protected abstract void clearProfiles();

    protected abstract void invalidateProfile(long steamId, boolean avatar);

    protected boolean voiceSupported() {
        return true;
    }

    protected volatile NativeCallGate jniGate = new NativeCallGate();
    private final Object connectionLock = new Object();
    private final ConnectionRegistry connectionOwners = new ConnectionRegistry();
    private boolean openingConnection;
    private final java.util.ArrayDeque<SteamNetConnectionStatusChangedCallback> earlyStatuses =
            new java.util.ArrayDeque<SteamNetConnectionStatusChangedCallback>();

    protected volatile boolean initialized = false;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    private volatile SteamUser steamUser;
    private volatile SteamFriends steamFriends;
    private volatile SteamUtils steamUtils;
    private SteamID mySteamID;
    private volatile SteamSocketsApi socketsApi;

    protected volatile S activeServer;
    protected volatile C activeClient;

    private volatile Thread callbackThread;
    private volatile Thread receiveThread;
    private volatile String lastBridgeOutcome = "none";

    private final Map<Integer, SteamConnectionStatus> statusByConnection =
            new ConcurrentHashMap<>();
    private final Map<Long, Integer> connectionBySteamId = new ConcurrentHashMap<>();

    private final Map<Integer, LoopbackBridge> loopbackByConnection = new ConcurrentHashMap<>();

    private final Map<Integer, LoopbackBridge> peerClosing = new ConcurrentHashMap<>();

    private final java.util.concurrent.locks.ReentrantLock receiveLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition hasConnections = receiveLock.newCondition();

    protected SteamRuntime() {}

    protected boolean loadNativeLibraries() {
        return SteamAPI.loadLibraries(new SteamNativeLibraries());
    }

    protected boolean initNativeApi() throws SteamException {
        return SteamAPI.init();
    }

    protected void shutdownNativeApi() {
        SteamAPI.shutdown();
        SteamSocketsApi.nativeShutdownComplete();
    }

    private void cleanupFailedInitialization() {
        try {
            shutdownNativeApi();
        } catch (RuntimeException | LinkageError failure) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamRuntime-warn-1",
                    "[SteamManager] Failed to clean up Steam initialization",
                    failure);
        }
    }

    public synchronized boolean init() {
        if (voiceSupported()) steambridge.proxy.SteamUdpProxy.getInstance().install(this);
        if (initialized) {
            LOG.debug("[SteamManager] init() called but already initialized.");
            return running.get();
        }

        jniGate = new NativeCallGate();
        LOG.debug("[SteamManager] Loading Steam native libraries...");
        try {
            if (!loadNativeLibraries()) {
                LOG.error("[SteamManager] Failed to load Steam native libraries.");
                cleanupFailedInitialization();
                return false;
            }
            LOG.debug("[SteamManager] Calling SteamAPI.init()...");
            if (!initNativeApi()) {
                LOG.error(
                        "[SteamManager] SteamAPI.init() returned false. Check Steam and"
                                + " steam_appid.txt.");
                cleanupFailedInitialization();
                return false;
            }
        } catch (SteamException | RuntimeException | LinkageError failure) {
            LOG.error("[SteamManager] Steam initialization failed", failure);
            cleanupFailedInitialization();
            return false;
        }

        try {

            SteamOffsets.validateLayout();

            steamUser = new SteamUser(new SteamUserCallbackAdapter());
            steamFriends = new SteamFriends(new SteamFriendsCallbackAdapter());
            steamUtils = new SteamUtils(new SteamUtilsCallbackAdapter());
            mySteamID = steamUser.getSteamID();
            socketsApi = SteamSocketsApi.load();
            socketsApi.installConnectionStatusCallback(this::onConnectionStatusChanged);

            socketsApi.configureForGameTraffic(
                    steambridge.SteamBridgeConfig.settings().allowWithoutAuth,
                    steambridge.SteamBridgeConfig.settings().stunServers,
                    steambridge.SteamBridgeConfig.settings().timeoutInitialSec,
                    steambridge.SteamBridgeConfig.settings().timeoutConnectedSec);
            socketsApi.initRelayNetworkAccess();
        } catch (Throwable t) {
            LOG.error(
                    "[SteamManager] Failed to initialize SteamNetworkingSockets: {}",
                    t.getMessage(),
                    t);
            if (socketsApi != null) {
                try {
                    socketsApi.dispose();
                } catch (Throwable cleanupFailure) {
                    t.addSuppressed(cleanupFailure);
                }
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
            cleanupFailedInitialization();
            return false;
        }

        initialized = true;
        running.set(true);
        startCallbackThread();
        startReceiveThread();

        LOG.debug(
                "[SteamManager] Steam initialized OK. My SteamID={}"
                        + " transport=ISteamNetworkingSockets",
                SteamNativeHandle.getNativeHandle(mySteamID));
        return true;
    }

    private void scheduleShutdown(final NativeCallGate runtime) {
        Thread cleanup =
                new Thread(
                        () -> {
                            synchronized (this) {
                                if (jniGate == runtime && initialized) shutdown();
                            }
                        },
                        "SteamBridge-Shutdown");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    public void shutdown() {
        if (Thread.currentThread() == callbackThread || Thread.currentThread() == receiveThread) {
            scheduleShutdown(jniGate);
            return;
        }
        synchronized (this) {
            if (!initialized) {
                return;
            }

            LOG.debug("[SteamManager] Shutting down...");
            running.set(false);
            signalReceiveWake();

            joinBackgroundThread(callbackThread);
            joinBackgroundThread(receiveThread);
            if ((callbackThread != null && callbackThread.isAlive())
                    || (receiveThread != null && receiveThread.isAlive())) {
                LOG.error(
                        "Steam driver did not stop; native resources remain owned until it exits.");
                return;
            }
            callbackThread = null;
            receiveThread = null;

            S server = activeServer;
            if (server != null) {
                server.stop();
            }

            C client = activeClient;
            if (client != null) {
                client.disconnect();
            }

            statusByConnection.clear();
            connectionBySteamId.clear();
            for (LoopbackBridge bridge : loopbackByConnection.values()) bridge.closeLocalOnly();
            loopbackByConnection.clear();
            for (LoopbackBridge bridge : peerClosing.values()) bridge.abortLocal();
            peerClosing.clear();
            connectionOwners.clear();

            if (socketsApi != null) {
                socketsApi.dispose();
                socketsApi = null;
            }

            jniGate.stopAndRun(
                    () -> {
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

                        shutdownNativeApi();
                    });
            clearProfiles();
            initialized = false;
            LOG.debug("[SteamManager] Shutdown complete.");
        }
    }

    public synchronized boolean reinit() {
        LOG.debug("[SteamManager] reinit() requested.");
        shutdown();
        if (initialized) return false;
        return init();
    }

    private void startCallbackThread() {
        final NativeCallGate runtime = jniGate;
        callbackThread =
                new Thread(
                        () -> {
                            LOG.debug("[SteamManager] Callback thread started.");
                            while (running.get()) {
                                try {
                                    SteamAPI.runCallbacks();
                                    SteamSocketsApi api = socketsApi;
                                    if (api != null) {
                                        api.runCallbacks();
                                    }

                                    Thread.sleep(8);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception e) {
                                    ProblemLog.error(
                                            LOG,
                                            LOG_LIMITER,
                                            "callback-loop",
                                            "[SteamManager] Callback loop failed",
                                            e);
                                }
                            }
                            LOG.debug("[SteamManager] Callback thread stopped.");
                            if (initialized && !running.get()) scheduleShutdown(runtime);
                        },
                        "SteamBridge-Callbacks");
        callbackThread.setDaemon(true);
        callbackThread.start();
    }

    private void startReceiveThread() {
        final NativeCallGate runtime = jniGate;
        receiveThread =
                new Thread(
                        () -> {
                            LOG.debug("[SteamManager] Receive thread started.");
                            ReceiveBackoff backoff = new ReceiveBackoff();
                            while (running.get()) {
                                try {

                                    if (statusByConnection.isEmpty()) {
                                        backoff.reset();
                                        receiveLock.lock();
                                        try {
                                            if (statusByConnection.isEmpty() && running.get()) {
                                                hasConnections.await(
                                                        1, java.util.concurrent.TimeUnit.SECONDS);
                                            }
                                        } finally {
                                            receiveLock.unlock();
                                        }
                                        continue;
                                    }

                                    boolean didWork = false;

                                    for (Integer connection : statusByConnection.keySet()) {

                                        for (int batches = 0;
                                                running.get()
                                                        && batches < 4
                                                        && drainConnection(connection);
                                                batches++) {
                                            didWork = true;
                                        }
                                    }

                                    int pause = backoff.afterPoll(didWork);
                                    if (pause > 0) Thread.sleep(pause);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception e) {
                                    ProblemLog.error(
                                            LOG,
                                            LOG_LIMITER,
                                            "receive-loop",
                                            "[SteamManager] Receive loop failed",
                                            e);
                                }
                            }
                            LOG.debug("[SteamManager] Receive thread stopped.");
                            if (initialized && !running.get()) scheduleShutdown(runtime);
                        },
                        "SteamBridge-Receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    protected void signalReceiveWake() {
        receiveLock.lock();
        try {
            hasConnections.signalAll();
        } finally {
            receiveLock.unlock();
        }
    }

    private void joinBackgroundThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(3000);
            if (thread.isAlive()) {
                ProblemLog.warn(
                        LOG,
                        LOG_LIMITER,
                        "SteamRuntime-warn-2",
                        "[SteamManager] {} did not stop within 3s of shutdown.",
                        thread.getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected InboundMessage[] pollMessages(int connection, int maximum) {
        SteamSocketsApi api = socketsApi;
        return api == null ? null : api.receiveMessages(connection, maximum);
    }

    boolean drainConnection(int connection) {
        try {
            return receiveConnection(connection);
        } catch (RuntimeException failure) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamRuntime-warn-3",
                    "[SteamBridge] Native receive failed for connection {}",
                    connection,
                    failure);
            LoopbackBridge bridge = loopbackByConnection.get(connection);
            if (bridge != null)
                bridge.fail("Native receive failed: " + failure.getClass().getSimpleName());
            else {
                steambridge.proxy.SteamUdpProxy.getInstance().onLocalConnectionClosed(connection);
                closeConnection(
                        connection, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, "Native receive failed");
            }
            return false;
        }
    }

    private boolean receiveConnection(int connection) {
        if (connection == 0) return false;
        LoopbackBridge bridge = loopbackByConnection.get(connection);
        if (bridge != null) {
            synchronized (bridge) {
                if (peerClosing.containsKey(connection)) return false;
                int maximum = bridge.receiveBatchLimit(64);
                if (maximum == 0) return false;
                InboundMessage[] batch = pollMessages(connection, maximum);
                if (batch == null || batch.length == 0) return false;
                bridge.deliverBatchFromSteam(batch);
                return true;
            }
        }
        steambridge.proxy.SteamUdpProxy proxy = steambridge.proxy.SteamUdpProxy.getInstance();
        boolean client = proxy.ownsClientConn(connection);
        if (!client && !proxy.ownsServerConn(connection)) return false;
        InboundMessage[] batch = pollMessages(connection, 64);
        if (batch == null || batch.length == 0) return false;
        for (InboundMessage message : batch) {
            if (message == null || message.getData().length == 0) continue;
            if (client) proxy.deliverFromSteamToClient(message.getData());
            else proxy.deliverFromSteamToServer(connection, message.getData());
        }
        return true;
    }

    @Override
    public boolean drainPeerMessages(LoopbackBridge bridge) {
        synchronized (bridge) {
            if (peerClosing.get(bridge.connectionHandle()) != bridge) return true;
            if (!running.get()) return true;
            for (int attempt = 0; attempt < 4; attempt++) {
                int maximum = bridge.receiveBatchLimit(64);
                if (maximum == 0) return false;
                InboundMessage[] batch = pollMessages(bridge.connectionHandle(), maximum);
                if (batch == null || batch.length == 0) return true;
                bridge.deliverBatchFromSteam(batch);
            }
            return false;
        }
    }

    private void onConnectionStatusChanged(SteamNetConnectionStatusChangedCallback event) {
        synchronized (connectionLock) {
            if (openingConnection) {
                if (earlyStatuses.size() >= 64)
                    throw new IllegalStateException("Too many early callbacks");
                SteamNetConnectionStatusChangedCallback copy =
                        new SteamNetConnectionStatusChangedCallback();
                byte[] bytes = event.getPointer().getByteArray(0, event.size());
                copy.getPointer().write(0, bytes, 0, bytes.length);
                copy.read();
                earlyStatuses.addLast(copy);
                return;
            }
        }
        dispatchConnectionStatus(event);
    }

    private void dispatchConnectionStatus(SteamNetConnectionStatusChangedCallback event) {
        SteamSocketsApi api = socketsApi;
        if (api == null) {
            return;
        }

        int connection = event.m_hConn;
        long remoteSteamID =
                (long) (event.m_info.m_identityRemote.m_data[0] & 0xFF)
                        | ((long) (event.m_info.m_identityRemote.m_data[1] & 0xFF) << 8)
                        | ((long) (event.m_info.m_identityRemote.m_data[2] & 0xFF) << 16)
                        | ((long) (event.m_info.m_identityRemote.m_data[3] & 0xFF) << 24)
                        | ((long) (event.m_info.m_identityRemote.m_data[4] & 0xFF) << 32)
                        | ((long) (event.m_info.m_identityRemote.m_data[5] & 0xFF) << 40)
                        | ((long) (event.m_info.m_identityRemote.m_data[6] & 0xFF) << 48)
                        | ((long) (event.m_info.m_identityRemote.m_data[7] & 0xFF) << 56);
        SteamConnectionStatus status =
                SteamConnectionStatus.fromCallback(
                        connection, remoteSteamID, event, api.snapshotConnection(connection));
        if (event.m_info.m_hListenSocket == 0
                && connectionOwners.get(connection) == null
                && (activeServer == null || !activeServer.ownsConnection(connection))) return;

        if (status.isTerminal()) {
            LoopbackBridge bridge = loopbackByConnection.get(connection);
            if (bridge != null) {
                synchronized (bridge) {
                    peerClosing.putIfAbsent(connection, bridge);
                    bridge.steamClosed(status.describeState());
                    if (bridge.isClosed()) peerClosing.remove(connection, bridge);
                }
            }
        }

        if (!status.isTerminal()) rememberStatus(status);

        boolean handled = false;

        S server = activeServer;
        if (server != null
                && (server.ownsListenSocket(event.m_info.m_hListenSocket)
                        || server.ownsConnection(connection))) {
            server.onConnectionStatusChanged(connection, remoteSteamID, status, event.m_eOldState);
            handled = true;
        }

        steambridge.proxy.SteamUdpProxy udpProxy = steambridge.proxy.SteamUdpProxy.getInstance();
        if (!handled) {
            if (udpProxy.ownsListenSocket(event.m_info.m_hListenSocket)
                    || udpProxy.ownsServerConn(connection)) {
                udpProxy.onServerConnectionStatusChanged(
                        connection, remoteSteamID, status, event.m_eOldState);
                handled = true;
            } else if (udpProxy.ownsClientConn(connection)
                    || connectionOwners.owns(connection, udpProxy)) {
                udpProxy.onClientConnectionStatusChanged(
                        connection, remoteSteamID, status, event.m_eOldState);
                handled = true;
            }
        }

        C client = activeClient;
        if (!handled && client != null && client.ownsConnection(connection, remoteSteamID)) {
            client.onConnectionStatusChanged(connection, remoteSteamID, status, event.m_eOldState);
            handled = true;
        }

        if (!handled) {
            if (event.m_info.m_hListenSocket != 0 && server == null) {
                closeConnection(
                        connection, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, "Host is not running");
            }
        }

        if (SteamSocketsApi.isTerminalState(status.getState())
                && !peerClosing.containsKey(connection)) {
            api.closeConnection(
                    connection,
                    SteamSocketsApi.APP_CLOSE_NORMAL,
                    "Terminal connection acknowledged",
                    false);
            forgetConnection(connection);
        }
    }

    private void forgetConnection(int connection) {
        connectionOwners.remove(connection);
        if (connection == 0) {
            return;
        }
        LoopbackBridge removed = loopbackByConnection.remove(connection);
        if (removed != null) removed.closeLocalOnly();
        SteamConnectionStatus status = statusByConnection.remove(connection);
        if (status != null && status.getSteamID() != 0L) {
            connectionBySteamId.remove(status.getSteamID(), connection);
        }
    }

    private void rememberStatus(SteamConnectionStatus status) {
        if (status == null || status.getConnectionHandle() == 0) {
            return;
        }

        SteamConnectionStatus previous =
                statusByConnection.put(status.getConnectionHandle(), status);
        if (previous == null) {

            signalReceiveWake();
        }
        if (status.getSteamID() != 0L) {
            ConnectionRegistry.Entry owner = connectionOwners.get(status.getConnectionHandle());
            if ((owner != null && owner.kind() == ConnectionRegistry.Kind.GAME)
                    || (activeServer != null
                            && activeServer.ownsConnection(status.getConnectionHandle()))) {
                connectionBySteamId.put(status.getSteamID(), status.getConnectionHandle());
            }
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
        try (NativeCallGate.Lease nativeLease = acquireNativeCall()) {
            if (nativeLease == null) return false;

            SteamFriends friends = steamFriends;
            if (friends == null || remoteSteamID == 0L) {
                return false;
            }

            try {
                return friends.getFriendRelationship(SteamID.createFromNativeHandle(remoteSteamID))
                        == SteamFriends.FriendRelationship.Friend;
            } catch (Exception e) {
                ProblemLog.warn(
                        LOG,
                        LOG_LIMITER,
                        "SteamRuntime-warn-4",
                        "[SteamManager] Failed to resolve friend relationship for {}: {}",
                        remoteSteamID,
                        e.getMessage());
                return false;
            }
        }
    }

    public void requestUserInformation(long remoteSteamID) {
        try (NativeCallGate.Lease nativeLease = acquireNativeCall()) {
            if (nativeLease == null) return;

            SteamFriends friends = steamFriends;
            if (friends == null || remoteSteamID == 0L) {
                return;
            }

            try {
                friends.requestUserInformation(
                        SteamID.createFromNativeHandle(remoteSteamID), false);
            } catch (Exception e) {

            }
        }
    }

    public SteamConnectionStatus getConnectionStatus(long remoteSteamID) {
        Integer conn = connectionBySteamId.get(remoteSteamID);
        return conn != null
                ? getConnectionStatusByHandle(conn)
                : SteamConnectionStatus.unavailable(remoteSteamID, 0);
    }

    public boolean acceptConnection(int connection) {
        SteamSocketsApi api = socketsApi;
        return api != null && api.acceptConnection(connection);
    }

    public int sendMessageFromByteBuf(int connection, io.netty.buffer.ByteBuf data, int len) {
        SteamSocketsApi api = socketsApi;
        return api != null
                ? api.sendMessageFromByteBuf(connection, data, len, SteamSocketsApi.SEND_RELIABLE)
                : 0;
    }

    @Override
    public SendQueueState sendQueueState(int connection) {
        SteamSocketsApi api = socketsApi;
        return api == null ? SendQueueState.UNKNOWN : api.querySendQueue(connection);
    }

    public String diagnosticReport() {
        StringBuilder report = new StringBuilder("Steam Bridge diagnostics\n");
        report.append("java=")
                .append(LogText.safe(System.getProperty("java.runtime.version", "?")))
                .append('\n');
        report.append("os=")
                .append(LogText.safe(System.getProperty("os.name", "?")))
                .append(" arch=")
                .append(LogText.safe(System.getProperty("os.arch", "?")))
                .append('\n');
        report.append("initialized=")
                .append(initialized)
                .append(" running=")
                .append(running.get())
                .append('\n');
        report.append("client=")
                .append(
                        activeClient == null
                                ? "none"
                                : activeClient instanceof SteamClientBase
                                        ? ((SteamClientBase<?>) activeClient).getState()
                                        : "active")
                .append('\n');
        report.append("bridges=").append(loopbackByConnection.size()).append('\n');
        report.append("lastBridgeOutcome=").append(lastBridgeOutcome).append('\n');
        int index = 0;
        for (Map.Entry<Integer, LoopbackBridge> entry :
                new java.util.TreeMap<>(loopbackByConnection).entrySet()) {
            if (++index > 128) {
                report.append("remaining peers omitted\n");
                break;
            }
            report.append("peer#").append(index).append(' ');
            try {
                SteamConnectionStatus status = getConnectionStatusByHandle(entry.getKey());
                report.append("state=")
                        .append(status.describeState())
                        .append(" route=")
                        .append(status.describeRoute())
                        .append(" metricsKnown=")
                        .append(status.getPingMs() >= 0)
                        .append(" rttMs=")
                        .append(status.getPingMs())
                        .append(" nativeQueuedBytes=")
                        .append(status.getQueuedBytes())
                        .append(" nativeQueueMicros=")
                        .append(status.getQueueMicros())
                        .append(" endReason=")
                        .append(status.getEndReason())
                        .append(' ');
            } catch (RuntimeException unavailable) {
                report.append("metricsUnavailable=")
                        .append(unavailable.getClass().getSimpleName())
                        .append(' ');
            }
            report.append(entry.getValue().diagnosticSummary()).append('\n');
        }
        return report.toString();
    }

    public boolean saveDiagnostics() {
        try {
            java.nio.file.Path file =
                    DiagnosticReport.write(
                            steambridge.SteamBridgeConfig.gameDirectory(), diagnosticReport());
            LOG.debug("[SteamBridge] Diagnostics saved to {}", file);
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamRuntime-warn-5",
                    "[SteamBridge] Could not save diagnostics: {}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    public void sendVoiceBytes(int connection, byte[] data) {
        SteamSocketsApi api = socketsApi;
        if (api != null) {
            api.sendBytes(connection, data, SteamSocketsApi.SEND_UNRELIABLE_NO_NAGLE);
        }
    }

    public void registerLoopback(int connection, LoopbackBridge bridge) {
        if (connection == 0 || bridge == null) return;
        synchronized (connectionLock) {
            if (!hasBridgeOwner(connection)) {
                bridge.closeLocalOnly();
                return;
            }
            LoopbackBridge previous = loopbackByConnection.putIfAbsent(connection, bridge);
            if (previous != null && previous != bridge) {
                bridge.closeLocalOnly();
                return;
            }
            if (!hasBridgeOwner(connection) && loopbackByConnection.remove(connection, bridge))
                bridge.closeLocalOnly();
        }
    }

    public void unregisterLoopback(int connection) {
        if (connection != 0) {
            LoopbackBridge removed = loopbackByConnection.remove(connection);
            if (removed != null) removed.closeLocalOnly();
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

    public SteamConnectionStatus getConnectionStatus(SteamID remote) {
        return remote == null
                ? SteamConnectionStatus.unavailable(0L, 0)
                : getConnectionStatus(SteamNativeHandle.getNativeHandle(remote));
    }

    public void applyTransportMode(Enum<?> mode) {
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
        return closeConnection(connection, reason, debug, false);
    }

    private boolean closeConnection(int connection, int reason, String debug, boolean linger) {
        if (running.get() && peerClosing.containsKey(connection)) {
            forgetConnection(connection);
            return true;
        }
        SteamSocketsApi api = socketsApi;
        boolean closed = api != null && api.closeConnection(connection, reason, debug, linger);

        forgetConnection(connection);
        return closed;
    }

    public boolean isLoopbackRegistered(int connection) {
        return connection != 0 && loopbackByConnection.containsKey(connection);
    }

    public S getActiveServer() {
        return activeServer;
    }

    @Override
    public Object getVoiceSessionToken(long steamId) {
        S server = activeServer;
        return server == null ? null : server.getVoiceSessionToken(steamId);
    }

    public C getActiveClient() {
        return activeClient;
    }

    @SuppressWarnings("unchecked")
    public void setActiveServer(SteamGameServer server) {
        LOG.debug("[SteamManager] setActiveServer: {}", server != null ? "SET" : "CLEAR");
        activeServer = (S) server;
    }

    @SuppressWarnings("unchecked")
    public boolean claimActiveServer(SteamGameServer server) {
        synchronized (connectionLock) {
            if (activeServer != null && activeServer != server) return false;
            activeServer = (S) server;
            return true;
        }
    }

    public boolean clearActiveServer(SteamGameServer server) {
        synchronized (connectionLock) {
            if (activeServer != server) return false;
            activeServer = null;
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public void setActiveClient(SteamGameClient client) {
        LOG.debug("[SteamManager] setActiveClient: {}", client != null ? "SET" : "CLEAR");
        activeClient = (C) client;
    }

    private static class SteamUserCallbackAdapter implements SteamUserCallback {
        @Override
        public void onAuthSessionTicket(SteamAuthTicket authTicket, SteamResult result) {}

        @Override
        public void onValidateAuthTicket(
                SteamID steamID, SteamAuth.AuthSessionResponse response, SteamID ownerSteamID) {}

        @Override
        public void onMicroTxnAuthorization(int appID, long orderID, boolean authorized) {}

        @Override
        public void onEncryptedAppTicket(SteamResult result) {}
    }

    private class SteamFriendsCallbackAdapter implements SteamFriendsCallback {
        @Override
        public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
            invalidateProfile(SteamNativeHandle.getNativeHandle(steamID), false);
            if (change == SteamFriends.PersonaChange.Avatar)
                invalidateProfile(SteamNativeHandle.getNativeHandle(steamID), true);
        }

        @Override
        public void onGameOverlayActivated(boolean active, boolean userInitiated, int appID) {}

        @Override
        public void onGameLobbyJoinRequested(SteamID steamIDLobby, SteamID steamIDFriend) {}

        @Override
        public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
            invalidateProfile(SteamNativeHandle.getNativeHandle(steamID), true);
        }

        @Override
        public void onFriendRichPresenceUpdate(SteamID steamIDFriend, int appID) {}

        @Override
        public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {}

        @Override
        public void onGameServerChangeRequested(String server, String password) {}
    }

    public abstract void handleSteamShutdown();

    private class SteamUtilsCallbackAdapter implements SteamUtilsCallback {
        @Override
        public void onSteamShutdown() {
            LOG.debug("[SteamManager] onSteamShutdown callback fired.");
            SteamRuntime.this.handleSteamShutdown();
        }
    }

    public int connectP2P(SteamID remote, int port, Object owner, ConnectionRegistry.Kind kind) {
        int handle;
        java.util.ArrayList<SteamNetConnectionStatusChangedCallback> pending;
        synchronized (connectionLock) {
            SteamSocketsApi api = socketsApi;
            if (!running.get() || api == null) return 0;
            openingConnection = true;
            try {
                handle = api.connectP2P(SteamNativeHandle.getNativeHandle(remote), port);
            } finally {
                openingConnection = false;
            }
            if (handle != 0) connectionOwners.register(handle, owner, kind);
            pending =
                    new java.util.ArrayList<SteamNetConnectionStatusChangedCallback>(earlyStatuses);
            earlyStatuses.clear();
        }
        for (SteamNetConnectionStatusChangedCallback event : pending)
            dispatchConnectionStatus(event);
        return handle;
    }

    public boolean ownsClientConnection(int handle, SteamGameClient client) {
        return connectionOwners.owns(handle, client);
    }

    public boolean clearActiveClient(SteamGameClient client) {
        synchronized (connectionLock) {
            if (activeClient != client) return false;
            activeClient = null;
            return true;
        }
    }

    @Override
    public void onBridgeClosed(LoopbackBridge bridge, boolean closeSteam, String reason) {
        finishBridge(bridge, closeSteam, reason, false);
    }

    @Override
    public void onBridgeFinished(LoopbackBridge bridge, String reason) {
        finishBridge(bridge, true, reason, true);
    }

    private void finishBridge(
            LoopbackBridge bridge, boolean closeSteam, String reason, boolean linger) {
        int handle = bridge.connectionHandle();
        lastBridgeOutcome = CloseReason.classify(reason) + " " + bridge.diagnosticSummary();
        CloseReason category = CloseReason.classify(reason);
        boolean failure =
                category == CloseReason.NATIVE_RECEIVE
                        || category == CloseReason.NATIVE_SEND
                        || category == CloseReason.QUEUE_LIMIT
                        || category == CloseReason.DRAIN_TIMEOUT
                        || (reason != null
                                && (reason.startsWith("Local TCP error")
                                        || reason.startsWith("Local TCP write failed")));
        if (failure) {
            SteamConnectionStatus status = statusByConnection.get(handle);
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "bridge-" + category,
                    "[SteamBridge] Bridge failed conn={} category={} route={} reason={} {}",
                    handle,
                    category,
                    status == null ? "unknown" : status.describeRoute(),
                    reason,
                    bridge.diagnosticSummary());
        } else {
            LOG.debug(
                    "[SteamBridge] Bridge closed conn={} reason={} {}",
                    handle,
                    LogText.safe(reason),
                    bridge.diagnosticSummary());
        }
        if (peerClosing.remove(handle, bridge)) {
            SteamSocketsApi api = socketsApi;
            if (api != null)
                api.closeConnection(handle, SteamSocketsApi.APP_CLOSE_NORMAL, reason, false);
            forgetConnection(handle);
            return;
        }
        if (!loopbackByConnection.remove(handle, bridge) || !closeSteam) return;
        closeConnection(handle, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, reason, linger);
        C client = activeClient;
        if (client != null) client.onLocalBridgeClosed(handle, reason);
        S server = activeServer;
        if (server != null) server.onLocalBridgeClosed(handle);
    }

    public NativeCallGate.Lease acquireNativeCall() {
        if (!initialized || !running.get()) return null;
        return jniGate.enter();
    }

    public boolean ownsConnectionOwner(int handle, Object owner) {
        return connectionOwners.owns(handle, owner);
    }

    private boolean hasBridgeOwner(int handle) {
        C client = activeClient;
        if (client != null && client.isAlive() && connectionOwners.owns(handle, client))
            return true;
        S server = activeServer;
        return server != null && server.isRunning() && server.ownsConnection(handle);
    }
}
