package steambridge.steam;

import static steambridge.panama.NativeConstants.*;

import static java.lang.foreign.ValueLayout.*;

import io.netty.buffer.ByteBuf;

import steambridge.core.NativeCallGate;
import steambridge.core.QueueLimits;
import steambridge.panama.SteamAPI;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

public final class SteamSocketsApi {
    private static final ReceivedMessage[] NO_MESSAGES = new ReceivedMessage[0];
    public static final int RESULT_OK = 1, RESULT_LIMIT_EXCEEDED = 25;
    public static final int STATE_NONE = 0,
            STATE_CONNECTING = 1,
            STATE_FINDING_ROUTE = 2,
            STATE_CONNECTED = 3,
            STATE_CLOSED_BY_PEER = 4,
            STATE_PROBLEM_DETECTED_LOCALLY = 5,
            STATE_FIN_WAIT = -1,
            STATE_LINGER = -2,
            STATE_DEAD = -3;
    public static final int SEND_UNRELIABLE = 0,
            SEND_UNRELIABLE_NO_NAGLE = 1,
            SEND_RELIABLE = 8,
            SEND_RELIABLE_NO_NAGLE = 9,
            INFO_FLAG_RELAYED = 16;
    public static final int APP_CLOSE_NORMAL = 1000,
            APP_CLOSE_LOCAL_ERROR = 1001,
            APP_CLOSE_REMOTE_ERROR = 1002;
    private final NativeCallGate gate = new NativeCallGate();
    private final Arena callbacks;
    private final MemorySegment sockets, utils;
    private volatile ConnectionStatusHandler callback;
    private static final java.util.Queue<Arena> retainedCallbacks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void nativeShutdownComplete() {
        Arena arena;
        while ((arena = retainedCallbacks.poll()) != null) arena.close();
    }

    private SteamSocketsApi() {
        sockets =
                (MemorySegment)
                        SteamAPI.call("SteamAPI_SteamNetworkingSockets_SteamAPI_v012", ADDRESS);
        utils =
                (MemorySegment)
                        SteamAPI.call("SteamAPI_SteamNetworkingUtils_SteamAPI_v004", ADDRESS);
        if (sockets.address() == 0 || utils.address() == 0) {
            throw new IllegalStateException("Steam networking interfaces are unavailable");
        }
        callbacks = Arena.ofShared();
    }

    public static SteamSocketsApi load() {
        return new SteamSocketsApi();
    }

    public void installConnectionStatusCallback(ConnectionStatusHandler handler) {
        try (var lease = gate.enter()) {
            if (lease == null) throw new IllegalStateException("Steam sockets are closed");
            callback = handler;
            try {
                var target =
                        MethodHandles.lookup()
                                .findVirtual(
                                        SteamSocketsApi.class,
                                        "onStatus",
                                        MethodType.methodType(void.class, MemorySegment.class))
                                .bindTo(this);
                MemorySegment stub =
                        Linker.nativeLinker()
                                .upcallStub(target, SteamAPI.connectionStatusCallback(), callbacks);
                if ((byte)
                                SteamAPI.call(
                                        "SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged",
                                        JAVA_BYTE,
                                        utils,
                                        stub)
                        == 0)
                    throw new IllegalStateException(
                            "Steam rejected connection callback registration");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private void onStatus(MemorySegment pointer) {
        try {
            ConnectionStatusHandler target = callback;
            if (target != null)
                target.onConnectionStatusChanged(SteamOffsets.readCallback(pointer));
        } catch (Throwable failure) {
            org.apache.logging.log4j.LogManager.getLogger("steambridge")
                    .error("Steam FFM callback failed", failure);
        }
    }

    public void dispose() {
        gate.stopAndRun(
                () -> {
                    callback = null;
                    boolean removed = false;
                    try {
                        removed =
                                (byte)
                                                SteamAPI.call(
                                                        "SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged",
                                                        JAVA_BYTE,
                                                        utils,
                                                        MemorySegment.NULL)
                                        != 0;
                    } catch (RuntimeException | LinkageError failure) {
                        org.apache.logging.log4j.LogManager.getLogger("steambridge")
                                .warn("Steam callback removal failed", failure);
                    }
                    if (removed) callbacks.close();
                    else retainedCallbacks.add(callbacks);
                });
    }

    public void runCallbacks() {
        try (var lease = gate.enter()) {
            if (lease != null)
                SteamAPI.call("SteamAPI_ISteamNetworkingSockets_RunCallbacks", null, sockets);
        }
    }

    public int createListenSocketP2P(int port) {
        try (var lease = gate.enter()) {
            return lease == null
                    ? 0
                    : (int)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P",
                                    JAVA_INT,
                                    sockets,
                                    port,
                                    0,
                                    MemorySegment.NULL);
        }
    }

    public boolean closeListenSocket(int handle) {
        try (var lease = gate.enter()) {
            return lease != null
                    && (byte)
                                    SteamAPI.call(
                                            "SteamAPI_ISteamNetworkingSockets_CloseListenSocket",
                                            JAVA_BYTE,
                                            sockets,
                                            handle)
                            != 0;
        }
    }

    public int connectP2P(long steamID, int port) {
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return 0;
            MemorySegment identity = arena.allocate(SteamOffsets.IDENTITY_BYTES, 8);
            SteamAPI.call("SteamAPI_SteamNetworkingIdentity_Clear", null, identity);
            SteamAPI.call("SteamAPI_SteamNetworkingIdentity_SetSteamID64", null, identity, steamID);
            return (int)
                    SteamAPI.call(
                            "SteamAPI_ISteamNetworkingSockets_ConnectP2P",
                            JAVA_INT,
                            sockets,
                            identity,
                            port,
                            0,
                            MemorySegment.NULL);
        }
    }

    public boolean acceptConnection(int handle) {
        try (var lease = gate.enter()) {
            return lease != null
                    && (int)
                                    SteamAPI.call(
                                            "SteamAPI_ISteamNetworkingSockets_AcceptConnection",
                                            JAVA_INT,
                                            sockets,
                                            handle)
                            == RESULT_OK;
        }
    }

    public boolean closeConnection(int handle, int reason, String debug, boolean linger) {
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return false;
            String safe = steambridge.core.LogText.safe(debug == null ? "" : debug);
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            MemorySegment message = arena.allocate(128);
            MemorySegment.copy(bytes, 0, message, JAVA_BYTE, 0, Math.min(bytes.length, 127));
            return (byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingSockets_CloseConnection",
                                    JAVA_BYTE,
                                    sockets,
                                    handle,
                                    reason,
                                    message,
                                    (byte) (linger ? 1 : 0))
                    != 0;
        }
    }

    public int sendBytes(int handle, byte[] data, int flags) {
        if (data == null || data.length > QueueLimits.MAX_NATIVE_MESSAGE_BYTES)
            throw new IllegalArgumentException("Invalid Steam payload");
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return 0;
            MemorySegment memory = arena.allocate(Math.max(1, data.length));
            MemorySegment.copy(data, 0, memory, JAVA_BYTE, 0, data.length);
            return (int)
                    SteamAPI.call(
                            "SteamAPI_ISteamNetworkingSockets_SendMessageToConnection",
                            JAVA_INT,
                            sockets,
                            handle,
                            memory,
                            data.length,
                            flags,
                            MemorySegment.NULL);
        }
    }

    public int sendMessageFromByteBuf(int handle, ByteBuf data, int length, int flags) {
        if (length < 0
                || length > data.readableBytes()
                || length > QueueLimits.MAX_NATIVE_MESSAGE_BYTES)
            throw new IllegalArgumentException("Invalid Steam send length");
        try (var lease = gate.enter()) {
            if (lease == null) return 0;
            if (data.isDirect() && data.nioBufferCount() == 1) {
                return sendPayload(
                        handle,
                        MemorySegment.ofBuffer(data.nioBuffer(data.readerIndex(), length)),
                        length,
                        flags);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment payload = arena.allocate(Math.max(1, length));
                data.getBytes(data.readerIndex(), payload.asByteBuffer().limit(length));
                return sendPayload(handle, payload, length, flags);
            }
        }
    }

    private int sendPayload(int handle, MemorySegment payload, int length, int flags) {
        return (int)
                SteamAPI.call(
                        "SteamAPI_ISteamNetworkingSockets_SendMessageToConnection",
                        JAVA_INT,
                        sockets,
                        handle,
                        payload,
                        length,
                        flags,
                        MemorySegment.NULL);
    }

    public ReceivedMessage[] receiveMessages(int handle) {
        return receiveMessages(handle, 64);
    }

    public ReceivedMessage[] receiveMessages(int handle, int maximum) {
        if (maximum <= 0 || handle == 0) return NO_MESSAGES;
        int limit = Math.max(1, Math.min(64, maximum));
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return NO_MESSAGES;
            MemorySegment pointers =
                    arena.allocate((long) limit * ADDRESS.byteSize(), ADDRESS.byteAlignment());
            int count =
                    (int)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection",
                                    JAVA_INT,
                                    sockets,
                                    handle,
                                    pointers,
                                    limit);
            if (count == 0) return NO_MESSAGES;
            return SteamMessageBatch.copy(
                    pointers,
                    count,
                    limit,
                    pointer ->
                            SteamAPI.call(
                                    "SteamAPI_SteamNetworkingMessage_t_Release", null, pointer));
        }
    }

    public SteamConnectionStatus snapshotConnection(int handle) {
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return SteamConnectionStatus.unavailable(0, handle);
            MemorySegment infoMemory = arena.allocate(SteamOffsets.CONNECTION_INFO_BYTES, 8);
            if ((byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingSockets_GetConnectionInfo",
                                    JAVA_BYTE,
                                    sockets,
                                    handle,
                                    infoMemory)
                    == 0) return SteamConnectionStatus.unavailable(0, handle);
            var info = SteamOffsets.readInfo(infoMemory);
            MemorySegment metrics = arena.allocate(SteamOffsets.REALTIME_BYTES, 8);
            boolean ready =
                    (int)
                                    SteamAPI.call(
                                            "SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus",
                                            JAVA_INT,
                                            sockets,
                                            handle,
                                            metrics,
                                            0,
                                            MemorySegment.NULL)
                            == RESULT_OK;
            long id = infoMemory.get(JAVA_LONG, 8);
            return new SteamConnectionStatus(
                    id,
                    handle,
                    true,
                    isConnectedState(info.m_eState),
                    isConnectingState(info.m_eState),
                    (info.m_nFlags & INFO_FLAG_RELAYED) != 0 || info.m_idPOPRelay != 0,
                    info.m_eState,
                    info.m_eEndReason,
                    cString(info.m_szEndDebug),
                    ready
                            ? Math.max(
                                    0,
                                    metrics.get(JAVA_INT, SteamOffsets.REALTIME_PENDING_UNRELIABLE)
                                            + metrics.get(
                                                    JAVA_INT,
                                                    SteamOffsets.REALTIME_PENDING_RELIABLE))
                            : 0,
                    ready ? metrics.get(JAVA_INT, SteamOffsets.REALTIME_PING) : -1,
                    ready
                            ? Math.max(0, metrics.get(JAVA_LONG, SteamOffsets.REALTIME_QUEUE_TIME))
                            : 0);
        }
    }

    public steambridge.core.SendQueueState querySendQueue(int handle) {
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null || handle == 0) return steambridge.core.SendQueueState.UNKNOWN;
            MemorySegment status = arena.allocate(SteamOffsets.REALTIME_BYTES, 8);
            int result =
                    (int)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus",
                                    JAVA_INT,
                                    sockets,
                                    handle,
                                    status,
                                    0,
                                    MemorySegment.NULL);
            if (result != RESULT_OK) return steambridge.core.SendQueueState.UNKNOWN;
            return new steambridge.core.SendQueueState(
                    true,
                    status.get(JAVA_INT, SteamOffsets.REALTIME_PENDING_RELIABLE),
                    status.get(JAVA_LONG, SteamOffsets.REALTIME_QUEUE_TIME),
                    status.get(JAVA_INT, 32));
        }
    }

    private void config(int key, int value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment memory = arena.allocate(JAVA_INT);
            memory.set(JAVA_INT, 0, value);
            if ((byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamNetworkingUtils_SetConfigValue",
                                    JAVA_BYTE,
                                    utils,
                                    key,
                                    CONFIG_SCOPE_GLOBAL,
                                    0L,
                                    CONFIG_TYPE_INT32,
                                    memory)
                    == 0) throw new IllegalStateException("Steam rejected configuration " + key);
        }
    }

    public void configureForGameTraffic(
            boolean allowWithoutAuth, String stun, int initial, int connected) {
        try (var lease = gate.enter();
                Arena arena = Arena.ofConfined()) {
            if (lease == null) return;
            config(CONFIG_SEND_BUFFER, steambridge.core.TransportPolicy.NATIVE_SEND_BUFFER_BYTES);
            config(
                    steambridge.core.TransportPolicy.SEND_RATE_MAX_CONFIG,
                    steambridge.core.TransportPolicy.SEND_RATE_MAX_BYTES);
            config(CONFIG_NAGLE, 0);
            config(CONFIG_ALLOW_UNAUTHENTICATED, allowWithoutAuth ? 1 : 0);
            config(CONFIG_TIMEOUT_INITIAL, Math.max(5, Math.min(120, initial)) * 1000);
            config(CONFIG_TIMEOUT_CONNECTED, Math.max(10, Math.min(300, connected)) * 1000);
            if (stun != null && !stun.isBlank()) {
                MemorySegment text = arena.allocateFrom(stun.trim());
                if ((byte)
                                SteamAPI.call(
                                        "SteamAPI_ISteamNetworkingUtils_SetConfigValue",
                                        JAVA_BYTE,
                                        utils,
                                        CONFIG_STUN,
                                        CONFIG_SCOPE_GLOBAL,
                                        0L,
                                        CONFIG_TYPE_STRING,
                                        text)
                        == 0) throw new IllegalStateException("Steam rejected STUN configuration");
            }
        }
    }

    public void applyTransportMode(Enum<?> mode) {
        try (var lease = gate.enter()) {
            if (lease == null) return;
            String selected = mode == null ? "AUTO" : mode.name();
            config(CONFIG_ICE_ENABLE, selected.equals("RELAY_ONLY") ? 0 : ICE_ENABLED_TYPES);
            config(CONFIG_ICE_PENALTY, selected.equals("RELAY_ONLY") ? 1000 : 0);
            config(CONFIG_SDR_PENALTY, selected.equals("P2P_ONLY") ? 1000 : 0);
        }
    }

    public void initRelayNetworkAccess() {
        try (var lease = gate.enter()) {
            if (lease != null)
                SteamAPI.call("SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess", null, utils);
        }
    }

    private static String cString(byte[] bytes) {
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) length++;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public static boolean isTerminalState(int state) {
        return state >= 4 || state < 0;
    }

    public static boolean isConnectingState(int state) {
        return state == 1 || state == 2;
    }

    public static boolean isConnectedState(int state) {
        return state == 3;
    }

    public static String stateName(int state) {
        return switch (state) {
            case 0 -> "None";
            case 1 -> "Connecting";
            case 2 -> "FindingRoute";
            case 3 -> "Connected";
            case 4 -> "ClosedByPeer";
            case 5 -> "ProblemDetectedLocally";
            case -1 -> "FinWait";
            case -2 -> "Linger";
            case -3 -> "Dead";
            default -> "Unknown(" + state + ")";
        };
    }

    public interface ConnectionStatusHandler {
        void onConnectionStatusChanged(SteamOffsets.SteamNetConnectionStatusChangedCallback event);
    }

    public record ReceivedMessage(int connection, byte[] data)
            implements steambridge.core.InboundMessage {
        public int getConnection() {
            return connection;
        }

        public byte[] getData() {
            return data;
        }
    }
}
