/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.Version;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.core.LogLimiter;
import steambridge.core.LogText;
import steambridge.core.NativeCallGate;
import steambridge.core.ProblemLog;
import steambridge.core.QueueLimits;
import steambridge.steam.SteamOffsets.*;

import java.io.File;

public final class SteamSocketsApi {
    private final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final Logger LOG = LogManager.getLogger("steambridge");
    private final NativeCallGate gate = new NativeCallGate();
    private final java.util.Set<NativeAllocation.Buffer> sendBuffers =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<NativeAllocation.Buffer, Boolean>());

    public static final int RESULT_OK = 1;
    public static final int RESULT_LIMIT_EXCEEDED =
            25; // k_EResultLimitExceeded - Steam send buffer full, recoverable

    public static final int STATE_NONE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_FINDING_ROUTE = 2;
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_CLOSED_BY_PEER = 4;
    public static final int STATE_PROBLEM_DETECTED_LOCALLY = 5;
    public static final int STATE_FIN_WAIT = -1;
    public static final int STATE_LINGER = -2;
    public static final int STATE_DEAD = -3;

    public static final int SEND_UNRELIABLE = 0;
    public static final int SEND_UNRELIABLE_NO_NAGLE = 1;
    public static final int SEND_RELIABLE = 8;
    public static final int SEND_RELIABLE_NO_NAGLE = 9;

    public static final int INFO_FLAG_RELAYED = 16;

    public static final int APP_CLOSE_NORMAL = 1000;
    public static final int APP_CLOSE_LOCAL_ERROR = 1001;
    public static final int APP_CLOSE_REMOTE_ERROR = 1002;

    private static final int RECV_BATCH = 64;

    private final FlatApi api;
    private final Pointer sockets;
    private final Pointer utils;
    private ConnectionStatusChangedCallback callbackRef;
    private volatile ConnectionStatusHandler statusHandler;
    private static final java.util.Queue<ConnectionStatusChangedCallback> retainedCallbacks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void nativeShutdownComplete() {
        retainedCallbacks.clear();
    }

    // Reusable buffer for batch-receive: RECV_BATCH pointers pre-allocated
    private final NativeAllocation.Buffer recvBatchMem =
            new NativeAllocation.Buffer((long) RECV_BATCH * Native.POINTER_SIZE);

    SteamSocketsApi(FlatApi api, Pointer sockets, Pointer utils) {
        this.api = api;
        this.sockets = sockets;
        this.utils = utils;
    }

    public static SteamSocketsApi load() {
        UnsatisfiedLinkError lastError = null;
        for (String name : getLibraryLoadCandidates()) {
            try {
                FlatApi api = (FlatApi) Native.loadLibrary(name, FlatApi.class);
                Pointer sockets = api.SteamAPI_SteamNetworkingSockets_SteamAPI_v012();
                Pointer utils = api.SteamAPI_SteamNetworkingUtils_SteamAPI_v004();
                if (isNull(sockets) || isNull(utils)) {
                    throw new UnsatisfiedLinkError(
                            "SteamNetworkingSockets interfaces were not returned by " + name);
                }
                return new SteamSocketsApi(api, sockets, utils);
            } catch (UnsatisfiedLinkError e) {
                lastError = e;
            }
        }

        throw lastError != null
                ? lastError
                : new UnsatisfiedLinkError("Unable to load steam_api for SteamNetworkingSockets");
    }

    // Correct ESteamNetworkingConfigValue enum values (from steamnetworkingsockets.h)
    private static final int CONFIG_SEND_BUFFER_SIZE = 9; // k_ESteamNetworkingConfig_SendBufferSize
    private static final int CONFIG_SEND_RATE_MAX = 11; // k_ESteamNetworkingConfig_SendRateMax
    private static final int CONFIG_NAGLE_TIME = 12; // k_ESteamNetworkingConfig_NagleTime
    private static final int CONFIG_ALLOW_WITHOUT_AUTH =
            23; // k_ESteamNetworkingConfig_IP_AllowWithoutAuth
    private static final int CONFIG_P2P_TIMEOUT = 24; // k_ESteamNetworkingConfig_TimeoutInitial
    private static final int CONFIG_TIMEOUT_CONNECTED =
            25; // k_ESteamNetworkingConfig_TimeoutConnected
    private static final int CONFIG_P2P_TRANSPORT_ICE_ENABLE =
            104; // k_ESteamNetworkingConfig_P2P_Transport_ICE_Enable
    private static final int CONFIG_P2P_TRANSPORT_ICE_PENALTY =
            105; // k_ESteamNetworkingConfig_P2P_Transport_ICE_Penalty
    private static final int CONFIG_P2P_TRANSPORT_SDR_PENALTY =
            106; // k_ESteamNetworkingConfig_P2P_Transport_SDR_Penalty

    private static final int CONFIG_P2P_STUN_SERVER_LIST =
            103; // k_ESteamNetworkingConfig_P2P_STUN_ServerList (String value)

    private static final int CONFIG_SCOPE_GLOBAL = 1;
    private static final int CONFIG_TYPE_INT32 = 1;
    private static final int CONFIG_TYPE_STRING = 4; // k_ESteamNetworkingConfig_String
    private static final int SEND_BUFFER_VAL =
            steambridge.core.TransportPolicy.NATIVE_SEND_BUFFER_BYTES;
    private static final int SEND_RATE_MAX_VAL =
            steambridge.core.TransportPolicy.SEND_RATE_MAX_BYTES;
    // k_nSteamNetworkingConfig_P2P_Transport_ICE_Enable_All = 0xFFFF (not 0x7FFFFFFF)
    private static final int P2P_TRANSPORT_ICE_ENABLE_ALL = 0xFFFF;
    private static final int P2P_TRANSPORT_ICE_PENALTY_VAL = 0;
    private static final int P2P_TRANSPORT_SDR_PENALTY_VAL =
            25; // lower SDR penalty -> prefer direct when both viable

    /**
     * Dynamically re-applies ICE/SDR transport preferences. Call before {@link
     * #createListenSocketP2P} or {@link #connectP2P}. AUTO: both ICE and SDR available, SDR penalty
     * = 25 ms -> prefers direct if both viable. P2P_ONLY: ICE enabled, SDR penalty = 9999 ms ->
     * relay only used as last resort. RELAY_ONLY: ICE disabled entirely -> always routes through
     * Valve SDR.
     */
    public void applyTransportMode(Enum<?> mode) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return;
            applyTransportModeImpl(mode);
        }
    }

    private void applyTransportModeImpl(Enum<?> mode) {
        try (NativeAllocation allocation = new NativeAllocation(4)) {
            NativeAllocation.Buffer val32 = allocation.buffer();
            switch (mode.name()) {
                case "P2P_ONLY":
                    val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_ICE_ENABLE,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    val32.setInt(0, 0);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_ICE_PENALTY,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    val32.setInt(0, 9999);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_SDR_PENALTY,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    LOG.debug(
                            "[SteamSocketsApi] Transport mode: P2P_ONLY (ICE=ALL,"
                                    + " SDRpenalty=9999ms)");
                    break;
                case "RELAY_ONLY":
                    val32.setInt(0, 0); // disable ICE
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_ICE_ENABLE,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    val32.setInt(0, 0);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_SDR_PENALTY,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    LOG.debug("[SteamSocketsApi] Transport mode: RELAY_ONLY (ICE=disabled)");
                    break;
                case "AUTO":
                default:
                    val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_ICE_ENABLE,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    val32.setInt(0, P2P_TRANSPORT_ICE_PENALTY_VAL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_ICE_PENALTY,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    val32.setInt(0, P2P_TRANSPORT_SDR_PENALTY_VAL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                            utils,
                            CONFIG_P2P_TRANSPORT_SDR_PENALTY,
                            CONFIG_SCOPE_GLOBAL,
                            0L,
                            CONFIG_TYPE_INT32,
                            val32);
                    LOG.debug(
                            "[SteamSocketsApi] Transport mode: AUTO (ICE=ALL, ICEpenalty={}ms,"
                                    + " SDRpenalty={}ms)",
                            P2P_TRANSPORT_ICE_PENALTY_VAL,
                            P2P_TRANSPORT_SDR_PENALTY_VAL);
                    break;
            }
        } catch (Throwable t) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamSocketsApi-warn-1",
                    "[SteamSocketsApi] applyTransportMode error: {}",
                    t.getMessage());
        }
    }

    public void initRelayNetworkAccess() {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return;
            initRelayNetworkAccessImpl();
        }
    }

    private void initRelayNetworkAccessImpl() {
        api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(utils);
    }

    public void configureForGameTraffic(
            boolean allowWithoutAuth,
            String stunServers,
            int initialSeconds,
            int connectedSeconds) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return;
            configureForGameTrafficImpl(
                    allowWithoutAuth, stunServers, initialSeconds, connectedSeconds);
        }
    }

    private void configureForGameTrafficImpl(
            boolean allowWithoutAuth,
            String stunServers,
            int initialSeconds,
            int connectedSeconds) {
        try (NativeAllocation allocation = new NativeAllocation(4)) {
            NativeAllocation.Buffer val32 = allocation.buffer();

            int timeoutInitialMs = Math.max(5, Math.min(120, initialSeconds)) * 1000;
            int timeoutConnectedMs = Math.max(10, Math.min(300, connectedSeconds)) * 1000;

            boolean stunOk = false;
            if (stunServers != null && stunServers.trim().length() > 0) {
                byte[] stunBytes =
                        (stunServers.trim() + "\0")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (NativeAllocation stunAllocation = new NativeAllocation(stunBytes.length)) {
                    NativeAllocation.Buffer stunMem = stunAllocation.buffer();
                    stunMem.write(0, stunBytes, 0, stunBytes.length);
                    stunOk =
                            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                            utils,
                                            CONFIG_P2P_STUN_SERVER_LIST,
                                            CONFIG_SCOPE_GLOBAL,
                                            0L,
                                            CONFIG_TYPE_STRING,
                                            stunMem)
                                    != 0;
                    LOG.debug(
                            "[SteamSocketsApi] STUN server list {}: {}",
                            stunOk ? "set" : "FAILED",
                            LogText.safe(stunServers.trim()));
                }
            }

            val32.setInt(0, timeoutInitialMs);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_P2P_TIMEOUT, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);

            val32.setInt(0, timeoutConnectedMs);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils,
                    CONFIG_TIMEOUT_CONNECTED,
                    CONFIG_SCOPE_GLOBAL,
                    0L,
                    CONFIG_TYPE_INT32,
                    val32);

            val32.setInt(0, allowWithoutAuth ? 1 : 0);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils,
                    CONFIG_ALLOW_WITHOUT_AUTH,
                    CONFIG_SCOPE_GLOBAL,
                    0L,
                    CONFIG_TYPE_INT32,
                    val32);

            val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
            boolean iceOk =
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                    utils,
                                    CONFIG_P2P_TRANSPORT_ICE_ENABLE,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    val32)
                            != 0;

            val32.setInt(0, P2P_TRANSPORT_ICE_PENALTY_VAL);
            boolean icePenaltyOk =
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                    utils,
                                    CONFIG_P2P_TRANSPORT_ICE_PENALTY,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    val32)
                            != 0;
            val32.setInt(0, P2P_TRANSPORT_SDR_PENALTY_VAL);
            boolean sdrPenaltyOk =
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                    utils,
                                    CONFIG_P2P_TRANSPORT_SDR_PENALTY,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    val32)
                            != 0;

            val32.setInt(0, SEND_BUFFER_VAL);
            boolean ok =
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                    utils,
                                    CONFIG_SEND_BUFFER_SIZE,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    val32)
                            != 0;

            val32.setInt(0, 0);
            boolean nagleOk =
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                                    utils,
                                    CONFIG_NAGLE_TIME,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    val32)
                            != 0;

            val32.setInt(0, SEND_RATE_MAX_VAL);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_RATE_MAX, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);

            if (ok) {
                LOG.debug(
                        "[SteamSocketsApi] Steam config applied (TimeoutInitial={}s,"
                                + " TimeoutConnected={}s, Auth={}, Buffer={}MB, RateMax={}MB/s,"
                                + " ICE=ALL, ICEPenalty={}ms, SDRPenalty={}ms, DirectPref={},"
                                + " Nagle={})",
                        timeoutInitialMs / 1000,
                        timeoutConnectedMs / 1000,
                        allowWithoutAuth ? 1 : 0,
                        SEND_BUFFER_VAL / 1024 / 1024,
                        SEND_RATE_MAX_VAL / 1024 / 1024,
                        P2P_TRANSPORT_ICE_PENALTY_VAL,
                        P2P_TRANSPORT_SDR_PENALTY_VAL,
                        iceOk && icePenaltyOk && sdrPenaltyOk ? "ENABLED" : "PARTIAL",
                        nagleOk ? "OFF" : "FAILED");
            } else {
                ProblemLog.warn(
                        LOG,
                        LOG_LIMITER,
                        "SteamSocketsApi-warn-2",
                        "[SteamSocketsApi] Failed to set Steam SendBufferSize (buffer config"
                                + " only)");
            }
        } catch (Throwable t) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "SteamSocketsApi-warn-3",
                    "[SteamSocketsApi] Error setting Steam configuration: {}",
                    t.getMessage());
        }
    }

    public void installConnectionStatusCallback(ConnectionStatusHandler handler) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return;
            installConnectionStatusCallbackImpl(handler);
        }
    }

    private void installConnectionStatusCallbackImpl(ConnectionStatusHandler handler) {
        statusHandler = handler;
        callbackRef =
                new ConnectionStatusChangedCallback() {
                    @Override
                    public void invoke(Pointer param) {
                        ConnectionStatusHandler target = statusHandler;
                        if (param == null || target == null) {
                            return;
                        }
                        SteamNetConnectionStatusChangedCallback event =
                                new SteamNetConnectionStatusChangedCallback(param);
                        event.read();
                        target.onConnectionStatusChanged(event);
                    }
                };
        if (api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
                        utils, callbackRef)
                == 0)
            throw new IllegalStateException("Steam rejected connection callback registration");
    }

    public void dispose() {
        gate.stopAndRun(
                () -> {
                    statusHandler = null;
                    boolean removed = false;
                    try {
                        removed =
                                api
                                                .SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
                                                        utils, null)
                                        != 0;
                    } catch (RuntimeException | LinkageError failure) {
                        ProblemLog.warn(
                                LOG,
                                LOG_LIMITER,
                                "SteamSocketsApi-warn-4",
                                "Steam callback removal failed",
                                failure);
                    }
                    if (!removed && callbackRef != null) {
                        retainedCallbacks.add(callbackRef);
                        ProblemLog.warn(
                                LOG,
                                LOG_LIMITER,
                                "SteamSocketsApi-warn-5",
                                "Retaining connection callback until Steam shutdown");
                    }
                    callbackRef = null;
                    for (NativeAllocation.Buffer memory : sendBuffers) memory.release();
                    sendBuffers.clear();
                    sendBuffer.remove();
                    TL_CONN_INFO.remove();
                    TL_REALTIME.remove();
                    recvBatchMem.release();
                });
    }

    public void runCallbacks() {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return;
            runCallbacksImpl();
        }
    }

    private void runCallbacksImpl() {
        api.SteamAPI_ISteamNetworkingSockets_RunCallbacks(sockets);
    }

    public int createListenSocketP2P(int virtualPort) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return 0;
            return createListenSocketP2PImpl(virtualPort);
        }
    }

    private int createListenSocketP2PImpl(int virtualPort) {
        if (virtualPort < 0 || virtualPort > 65534)
            throw new IllegalArgumentException("virtualPort must be between 0 and 65534");
        return api.SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(
                sockets, virtualPort, 0, null);
    }

    public boolean closeListenSocket(int listenSocket) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return false;
            return closeListenSocketImpl(listenSocket);
        }
    }

    private boolean closeListenSocketImpl(int listenSocket) {
        return listenSocket != 0
                && api.SteamAPI_ISteamNetworkingSockets_CloseListenSocket(sockets, listenSocket)
                        != 0;
    }

    public int connectP2P(long remoteSteamID, int virtualPort) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return 0;
            return connectP2PImpl(remoteSteamID, virtualPort);
        }
    }

    private int connectP2PImpl(long remoteSteamID, int virtualPort) {
        if (virtualPort < 0 || virtualPort > 65534)
            throw new IllegalArgumentException("virtualPort must be between 0 and 65534");
        SteamNetworkingIdentity identity = new SteamNetworkingIdentity();
        identity.write();
        api.SteamAPI_SteamNetworkingIdentity_Clear(identity.getPointer());
        api.SteamAPI_SteamNetworkingIdentity_SetSteamID64(identity.getPointer(), remoteSteamID);
        return api.SteamAPI_ISteamNetworkingSockets_ConnectP2P(
                sockets, identity.getPointer(), virtualPort, 0, null);
    }

    public boolean acceptConnection(int connection) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return false;
            return acceptConnectionImpl(connection);
        }
    }

    private boolean acceptConnectionImpl(int connection) {
        return api.SteamAPI_ISteamNetworkingSockets_AcceptConnection(sockets, connection)
                == RESULT_OK;
    }

    public boolean closeConnection(int connection, int reason, String debug, boolean linger) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return false;
            return closeConnectionImpl(connection, reason, debug, linger);
        }
    }

    private boolean closeConnectionImpl(int connection, int reason, String debug, boolean linger) {
        if (connection == 0) {
            return false;
        }
        return api.SteamAPI_ISteamNetworkingSockets_CloseConnection(
                        sockets, connection, reason, debug, (byte) (linger ? 1 : 0))
                != 0;
    }

    private final ThreadLocal<NativeAllocation.Buffer> sendBuffer =
            ThreadLocal.withInitial(
                    () -> {
                        NativeAllocation.Buffer memory = new NativeAllocation.Buffer(65536);
                        sendBuffers.add(memory);
                        return memory;
                    });

    /** Raw send (voice / UDP proxy). */
    public int sendBytes(int connection, byte[] data, int flags) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return 3;
            return sendBytesImpl(connection, data, flags);
        }
    }

    private int sendBytesImpl(int connection, byte[] data, int flags) {
        if (connection == 0 || data == null || data.length == 0) return 0;
        if (data.length > QueueLimits.MAX_NATIVE_MESSAGE_BYTES)
            throw new IllegalArgumentException("Payload too large");
        NativeAllocation.Buffer payload = sendBuffer.get();
        if (payload.size() < data.length) {
            sendBuffers.remove(payload);
            payload.release();
            payload = new NativeAllocation.Buffer(data.length);
            sendBuffers.add(payload);
            sendBuffer.set(payload);
        }
        payload.write(0, data, 0, data.length);
        return api.SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                sockets, connection, payload, data.length, flags, (LongByReference) null);
    }

    public int sendMessageFromByteBuf(
            int connection, io.netty.buffer.ByteBuf data, int len, int flags) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return 3;
            return sendMessageFromByteBufImpl(connection, data, len, flags);
        }
    }

    private int sendMessageFromByteBufImpl(
            int connection, io.netty.buffer.ByteBuf data, int len, int flags) {
        if (connection == 0 || data == null || len <= 0) return 0;
        if (len > data.readableBytes() || len > QueueLimits.MAX_NATIVE_MESSAGE_BYTES)
            throw new IllegalArgumentException("Invalid payload length");

        NativeAllocation.Buffer payload = sendBuffer.get();
        if (payload.size() < len) {
            sendBuffers.remove(payload);
            payload.release();
            payload = new NativeAllocation.Buffer(len);
            sendBuffers.add(payload);
            sendBuffer.set(payload);
        }
        java.nio.ByteBuffer nioView = payload.getByteBuffer(0, len);
        nioView.clear();
        data.getBytes(data.readerIndex(), nioView);
        return api.SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                sockets, connection, payload, len, flags, (LongByReference) null);
    }

    public ReceivedMessage[] receiveMessages(int connection) {
        return receiveMessages(connection, RECV_BATCH);
    }

    private final long[] releasedMessages = new long[RECV_BATCH];

    public synchronized ReceivedMessage[] receiveMessages(int connection, int maximum) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null || connection == 0 || maximum <= 0) return null;
            int requested = Math.min(RECV_BATCH, maximum);
            recvBatchMem.clear();
            int received =
                    api.SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(
                            sockets, connection, recvBatchMem, requested);
            if (received == 0) return null;
            Throwable primary = null;
            try {
                if (received < 0 || received > requested)
                    throw new IllegalStateException("Invalid receive count");
                ReceivedMessage[] results = new ReceivedMessage[received];
                for (int i = 0; i < received; i++) {
                    Pointer message = recvBatchMem.getPointer((long) i * Native.POINTER_SIZE);
                    if (isNull(message)) throw new IllegalStateException("Missing native message");
                    for (int j = 0; j < i; j++) {
                        if (Pointer.nativeValue(
                                        recvBatchMem.getPointer((long) j * Native.POINTER_SIZE))
                                == Pointer.nativeValue(message))
                            throw new IllegalStateException("Duplicate native message");
                    }
                    int size = message.getInt(SteamOffsets.MSG_OFF_CBSIZE);
                    int conn = message.getInt(SteamOffsets.MSG_OFF_CONN);
                    Pointer data = message.getPointer(SteamOffsets.MSG_OFF_PDATA);
                    if (size < 0
                            || size > QueueLimits.MAX_NATIVE_MESSAGE_BYTES
                            || (size > 0 && isNull(data))) {
                        throw new IllegalStateException("Invalid native payload");
                    }
                    byte[] payload = size == 0 ? new byte[0] : copyPayload(data, size);
                    results[i] = new ReceivedMessage(conn, payload);
                }
                return results;
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                Throwable releaseFailure = null;
                int releasedCount = 0;
                for (int i = 0; i < Math.min(Math.max(received, 0), RECV_BATCH); i++) {
                    Pointer message = recvBatchMem.getPointer((long) i * Native.POINTER_SIZE);
                    recvBatchMem.setPointer((long) i * Native.POINTER_SIZE, null);
                    if (isNull(message)) continue;
                    long address = Pointer.nativeValue(message);
                    boolean duplicate = false;
                    for (int j = 0; j < releasedCount; j++) {
                        if (releasedMessages[j] == address) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) continue;
                    releasedMessages[releasedCount++] = address;
                    try {
                        api.SteamAPI_SteamNetworkingMessage_t_Release(message);
                    } catch (Throwable failure) {
                        if (releaseFailure == null) releaseFailure = failure;
                        else if (releaseFailure != failure) releaseFailure.addSuppressed(failure);
                    }
                }
                if (releaseFailure != null) {
                    if (primary != null) {
                        if (primary != releaseFailure) primary.addSuppressed(releaseFailure);
                    } else
                        throw new IllegalStateException(
                                "Steam message release failed", releaseFailure);
                }
            }
        }
    }

    byte[] copyPayload(Pointer data, int size) {
        return data.getByteArray(0, size);
    }

    private final ThreadLocal<SteamNetConnectionInfo> TL_CONN_INFO =
            ThreadLocal.withInitial(SteamNetConnectionInfo::new);
    private final ThreadLocal<SteamNetConnectionRealTimeStatus> TL_REALTIME =
            ThreadLocal.withInitial(SteamNetConnectionRealTimeStatus::new);

    public steambridge.core.SendQueueState querySendQueue(int connection) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null || connection == 0) return steambridge.core.SendQueueState.UNKNOWN;
            SteamNetConnectionRealTimeStatus status = TL_REALTIME.get();
            int result =
                    api.SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(
                            sockets, connection, status, 0, null);
            if (result != RESULT_OK) return steambridge.core.SendQueueState.UNKNOWN;
            status.read();
            return new steambridge.core.SendQueueState(
                    true,
                    status.m_cbPendingReliable,
                    status.m_usecQueueTime,
                    status.m_nSendRateBytesPerSecond);
        }
    }

    public SteamConnectionStatus snapshotConnection(int connection) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return SteamConnectionStatus.unavailable(0L, connection);
            return snapshotConnectionImpl(connection);
        }
    }

    private SteamConnectionStatus snapshotConnectionImpl(int connection) {
        if (connection == 0) {
            return SteamConnectionStatus.unavailable(0L, 0);
        }

        SteamNetConnectionInfo info = TL_CONN_INFO.get();
        if (api.SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(sockets, connection, info)
                == 0) {
            return SteamConnectionStatus.unavailable(0L, connection);
        }
        info.read();

        SteamNetConnectionRealTimeStatus realtime = TL_REALTIME.get();
        int quickResult =
                api.SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(
                        sockets, connection, realtime, 0, null);
        if (quickResult == RESULT_OK) {
            realtime.read();
        }

        long remoteSteamID = readSteamId(info.m_identityRemote);

        boolean usingRelay = (info.m_nFlags & INFO_FLAG_RELAYED) != 0 || info.m_idPOPRelay != 0;
        boolean connected = info.m_eState == STATE_CONNECTED;
        boolean connecting =
                info.m_eState == STATE_CONNECTING || info.m_eState == STATE_FINDING_ROUTE;

        int bytesQueued = 0;
        int ping = -1;
        long queueTimeMicros = 0L;
        if (quickResult == RESULT_OK) {
            bytesQueued =
                    Math.max(0, realtime.m_cbPendingReliable + realtime.m_cbPendingUnreliable);
            ping = realtime.m_nPing;
            queueTimeMicros = Math.max(0L, realtime.m_usecQueueTime);
        }

        String error = trimCString(info.m_szEndDebug);
        if (error.isEmpty() && info.m_eEndReason != 0) {
            error = "end=" + info.m_eEndReason;
        }

        return new SteamConnectionStatus(
                remoteSteamID,
                connection,
                true,
                connected,
                connecting,
                usingRelay,
                info.m_eState,
                info.m_eEndReason,
                error,
                bytesQueued,
                ping,
                queueTimeMicros);
    }

    public static String stateName(int state) {
        switch (state) {
            case STATE_NONE:
                return "None";
            case STATE_CONNECTING:
                return "Connecting";
            case STATE_FINDING_ROUTE:
                return "FindingRoute";
            case STATE_CONNECTED:
                return "Connected";
            case STATE_CLOSED_BY_PEER:
                return "ClosedByPeer";
            case STATE_PROBLEM_DETECTED_LOCALLY:
                return "ProblemDetectedLocally";
            case STATE_FIN_WAIT:
                return "FinWait";
            case STATE_LINGER:
                return "Linger";
            case STATE_DEAD:
                return "Dead";
            default:
                return "State(" + state + ")";
        }
    }

    public static boolean isTerminalState(int state) {
        return state == STATE_CLOSED_BY_PEER
                || state == STATE_PROBLEM_DETECTED_LOCALLY
                || state == STATE_FIN_WAIT
                || state == STATE_LINGER
                || state == STATE_DEAD
                || state == STATE_NONE;
    }

    public static boolean isConnectingState(int state) {
        return state == STATE_CONNECTING || state == STATE_FINDING_ROUTE;
    }

    public static boolean isConnectedState(int state) {
        return state == STATE_CONNECTED;
    }

    private static String trimCString(byte[] raw) {
        int len = 0;
        while (len < raw.length && raw[len] != 0) {
            len++;
        }
        return len == 0 ? "" : new String(raw, 0, len);
    }

    public long readSteamId(SteamNetworkingIdentity identity) {
        try (NativeCallGate.Lease lease = gate.enter()) {
            if (lease == null) return 0L;
            return readSteamIdImpl(identity);
        }
    }

    private long readSteamIdImpl(SteamNetworkingIdentity identity) {
        return identity == null
                ? 0L
                : api.SteamAPI_SteamNetworkingIdentity_GetSteamID64(identity.getPointer());
    }

    private static boolean isNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0L;
    }

    private static String[] getLibraryNames() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64 = arch.contains("64");

        if (os.contains("win")) {
            return is64 ? new String[] {"steam_api64", "steam_api"} : new String[] {"steam_api"};
        }
        return new String[] {"steam_api"};
    }

    private static String[] getLibraryLoadCandidates() {
        String[] baseNames = getLibraryNames();
        String[] pathCandidates = getLibraryPathCandidates();
        String[] combined = new String[pathCandidates.length + baseNames.length];

        System.arraycopy(pathCandidates, 0, combined, 0, pathCandidates.length);
        System.arraycopy(baseNames, 0, combined, pathCandidates.length, baseNames.length);
        String loaded = SteamNativeLibraries.loadedSteamApi();
        if (loaded == null) return combined;
        String[] withLoaded = new String[combined.length + 1];
        withLoaded[0] = loaded;
        System.arraycopy(combined, 0, withLoaded, 1, combined.length);
        return withLoaded;
    }

    private static String[] getLibraryPathCandidates() {
        String fileName = getPlatformLibraryFileName("steam_api");
        String version = Version.getVersion();
        String extractDirectory =
                System.getProperty(
                        "com.codedisaster.steamworks.SharedLibraryExtractDirectory",
                        "steamworks4j");
        String extractPath =
                System.getProperty("com.codedisaster.steamworks.SharedLibraryExtractPath", null);

        String folderName = extractDirectory + File.separator + version;
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>();

        if (extractPath != null && !extractPath.isEmpty()) {
            candidates.add(new File(extractPath, fileName).getAbsolutePath());
        }

        candidates.add(
                new File(
                                System.getProperty("java.io.tmpdir"),
                                folderName + File.separator + fileName)
                        .getAbsolutePath());
        candidates.add(
                new File(
                                System.getProperty("user.home"),
                                "." + folderName + File.separator + fileName)
                        .getAbsolutePath());
        candidates.add(new File(".tmp", folderName + File.separator + fileName).getAbsolutePath());

        return candidates.stream()
                .distinct()
                .filter(path -> new File(path).exists())
                .toArray(String[]::new);
    }

    private static String getPlatformLibraryFileName(String libName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64 = arch.contains("64");

        if (os.contains("win")) {
            return libName + (is64 ? "64" : "") + ".dll";
        }
        if (os.contains("mac")) {
            return "lib" + libName + ".dylib";
        }
        return "lib" + libName + ".so";
    }

    public interface ConnectionStatusHandler {
        void onConnectionStatusChanged(SteamNetConnectionStatusChangedCallback event);
    }

    public static final class ReceivedMessage implements steambridge.core.InboundMessage {
        private final int connection;
        private final byte[] data;

        private ReceivedMessage(int connection, byte[] data) {
            this.connection = connection;
            this.data = data;
        }

        public int getConnection() {
            return connection;
        }

        public byte[] getData() {
            return data;
        }
    }

    interface FlatApi extends Library {
        Pointer SteamAPI_SteamNetworkingSockets_SteamAPI_v012();

        Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

        void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer utils);

        byte SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
                Pointer utils, ConnectionStatusChangedCallback callback);

        void SteamAPI_ISteamNetworkingSockets_RunCallbacks(Pointer sockets);

        int SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(
                Pointer sockets, int localVirtualPort, int optionsCount, Pointer options);

        byte SteamAPI_ISteamNetworkingSockets_CloseListenSocket(Pointer sockets, int listenSocket);

        int SteamAPI_ISteamNetworkingSockets_ConnectP2P(
                Pointer sockets,
                Pointer identityRemote,
                int remoteVirtualPort,
                int optionsCount,
                Pointer options);

        int SteamAPI_ISteamNetworkingSockets_AcceptConnection(Pointer sockets, int connection);

        byte SteamAPI_ISteamNetworkingSockets_CloseConnection(
                Pointer sockets, int connection, int reason, String debug, byte linger);

        int SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                Pointer sockets,
                int connection,
                Pointer data,
                int size,
                int sendFlags,
                LongByReference messageNumber);

        int SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(
                Pointer sockets, int connection, Pointer messages, int maxMessages);

        byte SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(
                Pointer sockets, int connection, SteamNetConnectionInfo info);

        int SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(
                Pointer sockets,
                int connection,
                SteamNetConnectionRealTimeStatus status,
                int lanes,
                Pointer laneStatus);

        void SteamAPI_SteamNetworkingIdentity_Clear(Pointer identity);

        void SteamAPI_SteamNetworkingIdentity_SetSteamID64(Pointer identity, long steamID);

        long SteamAPI_SteamNetworkingIdentity_GetSteamID64(Pointer identity);

        void SteamAPI_SteamNetworkingMessage_t_Release(Pointer message);

        byte SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                Pointer utils,
                int eValue,
                int eScopeType,
                long scopeObj,
                int eDataType,
                Pointer pArg);
    }

    interface ConnectionStatusChangedCallback extends Callback {
        void invoke(Pointer param);
    }
}
