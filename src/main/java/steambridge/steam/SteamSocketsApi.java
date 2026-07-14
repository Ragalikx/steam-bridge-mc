/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.codedisaster.steamworks.Version;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import steambridge.SteamBridgeMod;
import steambridge.steam.SteamOffsets.*;

import java.util.Arrays;
import java.util.List;
import java.io.File;

public final class SteamSocketsApi {

    public static final int RESULT_OK = 1;
    public static final int RESULT_LIMIT_EXCEEDED = 25; // k_EResultLimitExceeded - Steam send buffer full, recoverable

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
    // Reusable buffer for batch-receive: RECV_BATCH pointers pre-allocated
    private final Memory recvBatchMem = new Memory((long) RECV_BATCH * Native.POINTER_SIZE);

    private SteamSocketsApi(FlatApi api, Pointer sockets, Pointer utils) {
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
                    throw new UnsatisfiedLinkError("SteamNetworkingSockets interfaces were not returned by " + name);
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
    private static final int CONFIG_SEND_BUFFER_SIZE          = 9;   // k_ESteamNetworkingConfig_SendBufferSize
    private static final int CONFIG_SEND_RATE_MIN             = 10;  // k_ESteamNetworkingConfig_SendRateMin
    private static final int CONFIG_SEND_RATE_MAX             = 11;  // k_ESteamNetworkingConfig_SendRateMax
    // k_ESteamNetworkingConfig_NagleTime: time (µs) Steam holds a packet waiting to coalesce
    // with others. 0 = disable Nagle entirely at the Steam connection level.
    private static final int CONFIG_NAGLE_TIME                = 12;  // k_ESteamNetworkingConfig_NagleTime
    private static final int CONFIG_ALLOW_WITHOUT_AUTH        = 23;  // k_ESteamNetworkingConfig_IP_AllowWithoutAuth
    private static final int CONFIG_P2P_TIMEOUT               = 24;  // k_ESteamNetworkingConfig_TimeoutInitial
    private static final int CONFIG_TIMEOUT_CONNECTED         = 25;  // k_ESteamNetworkingConfig_TimeoutConnected
    private static final int CONFIG_P2P_TRANSPORT_ICE_ENABLE  = 104; // k_ESteamNetworkingConfig_P2P_Transport_ICE_Enable
    private static final int CONFIG_P2P_TRANSPORT_ICE_PENALTY = 105; // k_ESteamNetworkingConfig_P2P_Transport_ICE_Penalty
    private static final int CONFIG_P2P_TRANSPORT_SDR_PENALTY = 106; // k_ESteamNetworkingConfig_P2P_Transport_SDR_Penalty

    private static final int CONFIG_P2P_STUN_SERVER_LIST     = 103; // k_ESteamNetworkingConfig_P2P_STUN_ServerList (String value)

    private static final int CONFIG_SCOPE_GLOBAL     = 1;
    private static final int CONFIG_SCOPE_CONNECTION  = 4;
    private static final int CONFIG_TYPE_INT32   = 1;
    private static final int CONFIG_TYPE_STRING  = 4;   // k_ESteamNetworkingConfig_String
    // STUN servers ICE uses to discover each peer's public address (server-reflexive candidate).
    // Without at least one, ICE cannot gather routable candidates and every connection silently
    // falls back to SDR relay even on a LAN. Two Google public STUN servers (primary + backup).
    private static final String DEFAULT_STUN_SERVERS = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";
    private static final int SEND_BUFFER_VAL     = 2 * 1024 * 1024; // 2MB; reduced to prevent ACK window overflow during bulk dimension loads
    private static final int SEND_RATE_MIN_VAL   = 512 * 1024;      // 512 KB/s conservative floor (keeps Steam from over-sending on a friend's poor link)
    private static final int SEND_RATE_MAX_VAL   = 8 * 1024 * 1024; // 8 MB/s ceiling only; lets big-modpack join bursts (registry sync + first chunks) ramp fast over relay. Steam's congestion control still governs the actual rate, so this never over-sends on a bad link. Lower toward 4MB/s to be gentler on Valve relays.
    // k_nSteamNetworkingConfig_P2P_Transport_ICE_Enable_All = 0xFFFF (not 0x7FFFFFFF)
    private static final int P2P_TRANSPORT_ICE_ENABLE_ALL = 0xFFFF;
    private static final int P2P_TRANSPORT_ICE_PENALTY_VAL = 0;
    private static final int P2P_TRANSPORT_SDR_PENALTY_VAL = 25; // lower SDR penalty -> prefer direct when both viable

    /**
     * Dynamically re-applies ICE/SDR transport preferences.
     * Call before {@link #createListenSocketP2P} or {@link #connectP2P}.
     * AUTO: both ICE and SDR available, SDR penalty = 25 ms -> prefers direct if both viable.
     * P2P_ONLY: ICE enabled, SDR penalty = 9999 ms -> relay only used as last resort.
     * RELAY_ONLY: ICE disabled entirely -> always routes through Valve SDR.
     */
    public void applyTransportMode(SteamServer.TransportMode mode) {
        try {
            Memory val32 = new Memory(4);
            switch (mode) {
                case P2P_ONLY:
                    val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_ICE_ENABLE, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    val32.setInt(0, 0);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_ICE_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    val32.setInt(0, 9999);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_SDR_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    SteamBridgeMod.LOG.info("[SteamSocketsApi] Transport mode: P2P_ONLY (ICE=ALL, SDRpenalty=9999ms)");
                    break;
                case RELAY_ONLY:
                    val32.setInt(0, 0); // disable ICE
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_ICE_ENABLE, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    val32.setInt(0, 0);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_SDR_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    SteamBridgeMod.LOG.info("[SteamSocketsApi] Transport mode: RELAY_ONLY (ICE=disabled)");
                    break;
                case AUTO:
                default:
                    val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_ICE_ENABLE, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    val32.setInt(0, P2P_TRANSPORT_ICE_PENALTY_VAL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_ICE_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    val32.setInt(0, P2P_TRANSPORT_SDR_PENALTY_VAL);
                    api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TRANSPORT_SDR_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);
                    SteamBridgeMod.LOG.info("[SteamSocketsApi] Transport mode: AUTO (ICE=ALL, ICEpenalty={}ms, SDRpenalty={}ms)",
                            P2P_TRANSPORT_ICE_PENALTY_VAL, P2P_TRANSPORT_SDR_PENALTY_VAL);
                    break;
            }
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamSocketsApi] applyTransportMode error: {}", t.getMessage());
        }
    }

    public void initRelayNetworkAccess() {
        api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(utils);
    }

    /**
     * Applies per-connection overrides for loopback / same-machine P2P links.
     * <p>
     * On a loopback connection there are no real bandwidth constraints, so this config:
     * <ul>
     *   <li>Remove send-rate caps ({@code SendRateMax = Integer.MAX_VALUE}): the OS loopback
     *       interface is effectively unlimited.</li>
     *   <li>Shrink the send buffer ({@code SendBufferSize = 256 KB}): a smaller buffer means
     *       Steam detects backpressure sooner, which reduces queuing latency.</li>
     *   <li>Keep Nagle at 0 µs (already set globally); confirmed per-connection here.</li>
     * </ul>
     * Must be called after the connection reaches {@code STATE_CONNECTED}.
     *
     * @param connection the Steam connection handle
     */
    public void applyLoopbackConnectionConfig(int connection) {
        if (connection == 0) return;
        try {
            Memory val32 = new Memory(4);

            // No rate limit: loopback / LAN has no real bandwidth ceiling
            val32.setInt(0, Integer.MAX_VALUE);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_RATE_MAX, CONFIG_SCOPE_CONNECTION, connection, CONFIG_TYPE_INT32, val32);

            val32.setInt(0, 8 * 1024 * 1024); // 8 MB/s min for fast local path
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_RATE_MIN, CONFIG_SCOPE_CONNECTION, connection, CONFIG_TYPE_INT32, val32);

            // Smaller per-connection buffer -> reduced queuing latency
            val32.setInt(0, 256 * 1024); // 256 KB
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_BUFFER_SIZE, CONFIG_SCOPE_CONNECTION, connection, CONFIG_TYPE_INT32, val32);

            // Confirm Nagle is off at connection level too
            val32.setInt(0, 0);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_NAGLE_TIME, CONFIG_SCOPE_CONNECTION, connection, CONFIG_TYPE_INT32, val32);

            SteamBridgeMod.LOG.info(
                "[SteamSocketsApi] Loopback config applied: conn={} (RateMax=unlimited, Buffer=256KB, Nagle=0)",
                connection);
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamSocketsApi] applyLoopbackConnectionConfig failed conn={}: {}",
                    connection, t.getMessage());
        }
    }

    public void configureForGameTraffic(boolean allowWithoutAuth) {
        try {
            Memory val32 = new Memory(4);

            // 0. STUN servers for ICE (direct P2P) candidate discovery. This is a String config
            //    value, so pArg points to the null-terminated UTF-8 string itself (NOT a pointer
            //    to a pointer). Steam copies the string synchronously, so the local Memory is safe
            //    to let go after the call. Must be set before initRelayNetworkAccess().
            byte[] stunBytes = (DEFAULT_STUN_SERVERS + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Memory stunMem = new Memory(stunBytes.length);
            stunMem.write(0, stunBytes, 0, stunBytes.length);
            boolean stunOk = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_P2P_STUN_SERVER_LIST, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_STRING, stunMem);
            SteamBridgeMod.LOG.info("[SteamSocketsApi] STUN server list {}: {}",
                    stunOk ? "set" : "FAILED", DEFAULT_STUN_SERVERS);

            // 1. Increase P2P initial-connection timeout to 30 s (value is in milliseconds)
            val32.setInt(0, 30_000);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_P2P_TIMEOUT, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);

            // 1b. set TimeoutConnected to 60 s.
            // Default is only 10 000 ms. When the client opens heavy GUIs (e.g. Galacticraft's
            // planet selection screen which loads 15+ planet textures), the MC client thread can
            // freeze for ~10 s without sending any game packets -> Steam Relay terminates the
            // connection ("Rx age server 10.6s relay 0.0s") -> crash on the still-rendering GUI.
            // 60 s gives plenty of headroom for heavy loading screens on any hardware.
            val32.setInt(0, 60_000);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_TIMEOUT_CONNECTED, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);

            // 2. Allow connection without auth if needed (helps with some NAT types)
            val32.setInt(0, allowWithoutAuth ? 1 : 0);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(utils, CONFIG_ALLOW_WITHOUT_AUTH, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32);

            // 3. Override Steam's user-default route policy and explicitly share all ICE candidates.
            //    Without this, users who disabled "faster connection" in Steam can be pinned to relay.
            val32.setInt(0, P2P_TRANSPORT_ICE_ENABLE_ALL);
            boolean iceOk = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_P2P_TRANSPORT_ICE_ENABLE, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            // 4. Prefer direct P2P when it is viable, but keep SDR available as a fallback.
            val32.setInt(0, P2P_TRANSPORT_ICE_PENALTY_VAL);
            boolean icePenaltyOk = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_P2P_TRANSPORT_ICE_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );
            val32.setInt(0, P2P_TRANSPORT_SDR_PENALTY_VAL);
            boolean sdrPenaltyOk = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_P2P_TRANSPORT_SDR_PENALTY, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            // 5. Set global send buffer size
            val32.setInt(0, SEND_BUFFER_VAL);
            boolean ok = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_BUFFER_SIZE, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            // 6. Nagle at Steam level: explicitly set to 0 us (disabled).
            //    SEND_RELIABLE_NO_NAGLE affects only individual sendMessage calls;
            //    the global NagleTime can still delay packets at the connection level.
            val32.setInt(0, 0);
            boolean nagleOk = api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_NAGLE_TIME, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            // 7. Send rate min: prevent Steam from throttling speed at session start
            //    (chunk loading happens right then)
            val32.setInt(0, SEND_RATE_MIN_VAL);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_RATE_MIN, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            // 8. Send rate max: cap at SEND_RATE_MAX_VAL (4 MB/s) to avoid relay flooding
            //    while staying well above any realistic LAN/chunk-load demand.
            val32.setInt(0, SEND_RATE_MAX_VAL);
            api.SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                    utils, CONFIG_SEND_RATE_MAX, CONFIG_SCOPE_GLOBAL, 0L, CONFIG_TYPE_INT32, val32
            );

            if (ok) {
                SteamBridgeMod.LOG.info(
                        "[SteamSocketsApi] Steam config applied (TimeoutInitial=30s, TimeoutConnected=60s, Auth={}, Buffer={}MB, Rate={}-{}MB/s, ICE=ALL, ICEPenalty={}ms, SDRPenalty={}ms, DirectPref={}, Nagle={})",
                        allowWithoutAuth ? 1 : 0,
                        SEND_BUFFER_VAL / 1024 / 1024,
                        SEND_RATE_MIN_VAL / 1024 / 1024,
                        SEND_RATE_MAX_VAL / 1024 / 1024,
                        P2P_TRANSPORT_ICE_PENALTY_VAL,
                        P2P_TRANSPORT_SDR_PENALTY_VAL,
                        iceOk && icePenaltyOk && sdrPenaltyOk ? "ENABLED" : "PARTIAL",
                        nagleOk ? "OFF" : "FAILED"
                );
            } else {
                SteamBridgeMod.LOG.warn("[SteamSocketsApi] Failed to set Steam SendBufferSize (buffer config only)");
            }
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamSocketsApi] Error setting Steam configuration: {}", t.getMessage());
        }
    }

    public void installConnectionStatusCallback(ConnectionStatusHandler handler) {
        callbackRef = new ConnectionStatusChangedCallback() {
            @Override
            public void invoke(Pointer param) {
                if (param == null || handler == null) {
                    return;
                }
                SteamNetConnectionStatusChangedCallback event =
                        new SteamNetConnectionStatusChangedCallback(param);
                event.read();
                handler.onConnectionStatusChanged(event);
            }
        };
        api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
                utils, callbackRef
        );
    }

    public void dispose() {
        api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(utils, null);
        callbackRef = null;
    }

    public void runCallbacks() {
        api.SteamAPI_ISteamNetworkingSockets_RunCallbacks(sockets);
    }

    public int createListenSocketP2P(int virtualPort) {
        return api.SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(
                sockets, virtualPort, 0, null
        );
    }

    public boolean closeListenSocket(int listenSocket) {
        return listenSocket != 0 && api.SteamAPI_ISteamNetworkingSockets_CloseListenSocket(sockets, listenSocket);
    }

    public int connectP2P(long remoteSteamID, int virtualPort) {
        SteamNetworkingIdentity identity = new SteamNetworkingIdentity();
        identity.write();
        api.SteamAPI_SteamNetworkingIdentity_Clear(identity.getPointer());
        api.SteamAPI_SteamNetworkingIdentity_SetSteamID64(identity.getPointer(), remoteSteamID);
        return api.SteamAPI_ISteamNetworkingSockets_ConnectP2P(
                sockets, identity.getPointer(), virtualPort, 0, null
        );
    }

    public boolean acceptConnection(int connection) {
        return api.SteamAPI_ISteamNetworkingSockets_AcceptConnection(sockets, connection) == RESULT_OK;
    }

    public boolean closeConnection(int connection, int reason, String debug, boolean linger) {
        if (connection == 0) {
            return false;
        }
        return api.SteamAPI_ISteamNetworkingSockets_CloseConnection(
                sockets, connection, reason, debug, linger
        );
    }

    private static final ThreadLocal<Memory> sendBuffer = ThreadLocal.withInitial(() -> new Memory(65536));

    /**
     * Send raw bytes (used by the UDP voice proxy). Writes into the ThreadLocal native buffer
     * and hands the pointer to SteamNetworkingSockets.
     */
    public int sendBytes(int connection, byte[] data, int flags) {
        if (connection == 0 || data == null || data.length == 0) return 0;
        Memory payload = sendBuffer.get();
        if (payload.size() < data.length) {
            payload = new Memory(data.length);
            sendBuffer.set(payload);
        }
        payload.write(0, data, 0, data.length);
        return api.SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                sockets, connection, payload, data.length, flags, (LongByReference) null
        );
    }

    /**
     * Zero-copy send from a Netty ByteBuf: writes directly into the pre-allocated ThreadLocal
     * native Memory via NIO ByteBuffer, avoiding an intermediate {@code byte[]} allocation.
     */
    public int sendMessageFromByteBuf(int connection, io.netty.buffer.ByteBuf data, int len, int flags) {
        if (connection == 0 || data == null || len == 0) return 0;

        Memory payload = sendBuffer.get();
        if (payload.size() < len) {
            payload = new Memory(len);
            sendBuffer.set(payload);
        }
        // One copy: ByteBuf -> NIO ByteBuffer view of native Memory (avoids intermediate byte[])
        java.nio.ByteBuffer nioView = payload.getByteBuffer(0, len);
        nioView.clear();
        data.getBytes(data.readerIndex(), nioView);
        return api.SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                sockets, connection, payload, len, flags, (LongByReference) null
        );
    }


    /**
     * Receives up to RECV_BATCH messages in a single JNA call.
     * Fields are read directly from native Pointer offsets; no JNA Structure allocation per message.
     * Returns null if no messages are available; otherwise an array (may contain null elements).
     */
    public ReceivedMessage[] receiveMessages(int connection) {
        if (connection == 0) return null;

        int received = api.SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(
                sockets, connection, recvBatchMem, RECV_BATCH
        );
        if (received <= 0) return null;

        ReceivedMessage[] results = new ReceivedMessage[received];
        for (int i = 0; i < received; i++) {
            Pointer msgPtr = recvBatchMem.getPointer((long) i * Native.POINTER_SIZE);
            if (msgPtr == null) continue;
            // Read fields directly by offset to avoid allocating a JNA Structure per message:
            //   +0  m_pData  (Pointer, 8 bytes)
            //   +8  m_cbSize (int32)
            //   +12 m_conn   (int32)
            int    cbSize  = msgPtr.getInt(SteamOffsets.MSG_OFF_CBSIZE);
            int    conn    = msgPtr.getInt(SteamOffsets.MSG_OFF_CONN);
            Pointer dataPtr = msgPtr.getPointer(SteamOffsets.MSG_OFF_PDATA);
            byte[] payload = (dataPtr != null && cbSize > 0)
                    ? dataPtr.getByteArray(0, cbSize)
                    : new byte[0];
            api.SteamAPI_SteamNetworkingMessage_t_Release(msgPtr);
            results[i] = new ReceivedMessage(conn, payload);
        }
        return results;
    }

    // ThreadLocal reuse for snapshotConnection; eliminates two JNA Structure allocations per call.
    // snapshotConnection() is called from the callback thread and receive thread independently,
    // so ThreadLocal gives each thread its own private instance (safe for concurrent use).
    private static final ThreadLocal<SteamNetConnectionInfo> TL_CONN_INFO =
            ThreadLocal.withInitial(SteamNetConnectionInfo::new);
    private static final ThreadLocal<SteamNetConnectionRealTimeStatus> TL_REALTIME =
            ThreadLocal.withInitial(SteamNetConnectionRealTimeStatus::new);

    public SteamConnectionStatus snapshotConnection(int connection) {
        if (connection == 0) {
            return SteamConnectionStatus.unavailable(0L, 0);
        }

        SteamNetConnectionInfo info = TL_CONN_INFO.get();
        if (!api.SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(sockets, connection, info)) {
            return SteamConnectionStatus.unavailable(0L, connection);
        }
        info.read();

        SteamNetConnectionRealTimeStatus realtime = TL_REALTIME.get();
        int quickResult = api.SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(
                sockets, connection, realtime, 0, null
        );
        if (quickResult == STATE_CONNECTED || quickResult == STATE_CONNECTING || quickResult == STATE_FINDING_ROUTE) {
            realtime.read();
        }

        long remoteSteamID = readSteamId(info.m_identityRemote);

        boolean usingRelay = (info.m_nFlags & INFO_FLAG_RELAYED) != 0 || info.m_idPOPRelay != 0;
        boolean connected = info.m_eState == STATE_CONNECTED;
        boolean connecting = info.m_eState == STATE_CONNECTING || info.m_eState == STATE_FINDING_ROUTE;

        int bytesQueued = 0;
        int packetsQueued = 0;
        int ping = -1;
        float inBytesPerSecond = 0f;
        float outBytesPerSecond = 0f;
        long queueTimeMicros = 0L;
        if (quickResult == STATE_CONNECTED || quickResult == STATE_CONNECTING || quickResult == STATE_FINDING_ROUTE) {
            bytesQueued = Math.max(0, realtime.m_cbPendingReliable + realtime.m_cbPendingUnreliable);
            packetsQueued = Math.max(0, realtime.m_cbSentUnackedReliable);
            ping = realtime.m_nPing;
            inBytesPerSecond = Math.max(0f, realtime.m_flInBytesPerSec);
            outBytesPerSecond = Math.max(0f, realtime.m_flOutBytesPerSec);
            queueTimeMicros = Math.max(0L, realtime.m_usecQueueTime);
        }

        String error = trimCString(info.m_szEndDebug);
        if (error.isEmpty() && info.m_eEndReason != 0) {
            error = "end=" + info.m_eEndReason;
        }

        String description = trimCString(info.m_szConnectionDescription);

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
                packetsQueued,
                ping,
                info.m_idPOPRemote,
                info.m_idPOPRelay,
                inBytesPerSecond,
                outBytesPerSecond,
                queueTimeMicros,
                description
        );
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
        return identity == null ? 0L : api.SteamAPI_SteamNetworkingIdentity_GetSteamID64(identity.getPointer());
    }

    private static boolean isNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0L;
    }

    private static String[] getLibraryNames() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64 = arch.contains("64");

        if (os.contains("win")) {
            return is64
                    ? new String[] { "steam_api64", "steam_api" }
                    : new String[] { "steam_api" };
        }
        return new String[] { "steam_api" };
    }

    private static String[] getLibraryLoadCandidates() {
        String[] baseNames = getLibraryNames();
        String[] pathCandidates = getLibraryPathCandidates();
        String[] combined = new String[pathCandidates.length + baseNames.length];

        System.arraycopy(pathCandidates, 0, combined, 0, pathCandidates.length);
        System.arraycopy(baseNames, 0, combined, pathCandidates.length, baseNames.length);
        return combined;
    }

    private static String[] getLibraryPathCandidates() {
        String fileName = getPlatformLibraryFileName("steam_api");
        String version = Version.getVersion();
        String extractDirectory = System.getProperty(
                "com.codedisaster.steamworks.SharedLibraryExtractDirectory",
                "steamworks4j"
        );
        String extractPath = System.getProperty(
                "com.codedisaster.steamworks.SharedLibraryExtractPath",
                null
        );

        String folderName = extractDirectory + File.separator + version;
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>();

        if (extractPath != null && !extractPath.isEmpty()) {
            candidates.add(new File(extractPath, fileName).getAbsolutePath());
        }

        candidates.add(new File(System.getProperty("java.io.tmpdir"), folderName + File.separator + fileName).getAbsolutePath());
        candidates.add(new File(System.getProperty("user.home"), "." + folderName + File.separator + fileName).getAbsolutePath());
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

    public static final class ReceivedMessage {
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

    private interface FlatApi extends Library {
        Pointer SteamAPI_SteamNetworkingSockets_SteamAPI_v012();
        Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

        void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer utils);
        void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
                Pointer utils, ConnectionStatusChangedCallback callback
        );

        void SteamAPI_ISteamNetworkingSockets_RunCallbacks(Pointer sockets);
        int SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(
                Pointer sockets, int localVirtualPort, int optionsCount, Pointer options
        );
        boolean SteamAPI_ISteamNetworkingSockets_CloseListenSocket(Pointer sockets, int listenSocket);
        int SteamAPI_ISteamNetworkingSockets_ConnectP2P(
                Pointer sockets, Pointer identityRemote, int remoteVirtualPort, int optionsCount, Pointer options
        );
        int SteamAPI_ISteamNetworkingSockets_AcceptConnection(Pointer sockets, int connection);
        boolean SteamAPI_ISteamNetworkingSockets_CloseConnection(
                Pointer sockets, int connection, int reason, String debug, boolean linger
        );
        int SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(
                Pointer sockets, int connection, Pointer data, int size, int sendFlags, LongByReference messageNumber
        );
        int SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(
                Pointer sockets, int connection, Pointer messages, int maxMessages
        );
        boolean SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(
                Pointer sockets, int connection, SteamNetConnectionInfo info
        );
        int SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(
                Pointer sockets, int connection, SteamNetConnectionRealTimeStatus status, int lanes, Pointer laneStatus
        );

        void SteamAPI_SteamNetworkingIdentity_Clear(Pointer identity);
        void SteamAPI_SteamNetworkingIdentity_SetSteamID64(Pointer identity, long steamID);
        long SteamAPI_SteamNetworkingIdentity_GetSteamID64(Pointer identity);
        void SteamAPI_SteamNetworkingMessage_t_Release(Pointer message);
        boolean SteamAPI_ISteamNetworkingUtils_SetConfigValue(
                Pointer utils, int eValue, int eScopeType, long scopeObj,
                int eDataType, Pointer pArg
        );
    }

    private interface ConnectionStatusChangedCallback extends Callback {
        void invoke(Pointer param);
    }
}

