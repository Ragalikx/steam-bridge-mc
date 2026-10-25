package steambridge.steam;

import static java.lang.foreign.ValueLayout.*;

import steambridge.panama.SteamPlatform;

import java.lang.foreign.MemorySegment;

public final class SteamOffsets {
    private static final SteamPlatform PLATFORM = SteamPlatform.current();
    public static final long IDENTITY_BYTES = 136;
    public static final long CONNECTION_INFO_BYTES = 696;
    public static final long REALTIME_BYTES = 120;
    public static final long CALLBACK_BYTES = PLATFORM.callbackBytes();
    public static final long MESSAGE_BYTES = 216;

    public static final int IDENTITY_DATA = 8;
    public static final int IDENTITY_DATA_BYTES = 128;
    public static final int INFO_IDENTITY = 0;
    public static final int INFO_LISTEN = 144;
    public static final int INFO_POP_REMOTE = 168;
    public static final int INFO_POP_RELAY = 172;
    public static final int INFO_STATE = 176;
    public static final int INFO_END_REASON = 180;
    public static final int INFO_END_DEBUG = 184;
    public static final int INFO_DESCRIPTION = 312;
    public static final int INFO_FLAGS = 440;
    public static final int CALLBACK_CONNECTION = 0;
    public static final int CALLBACK_INFO = PLATFORM.callbackInfo();
    public static final int CALLBACK_OLD_STATE = PLATFORM.callbackOldState();
    public static final int MESSAGE_DATA = 0;
    public static final int MESSAGE_SIZE = 8;
    public static final int MESSAGE_CONNECTION = 12;
    public static final int REALTIME_PING = 4;
    public static final int REALTIME_PENDING_UNRELIABLE = 36;
    public static final int REALTIME_PENDING_RELIABLE = 40;
    public static final int REALTIME_SEND_BYTES = 20;
    public static final int REALTIME_RECEIVE_BYTES = 28;
    public static final int REALTIME_QUEUE_TIME = 48;
    public static final int DISPATCH_BYTES = PLATFORM.dispatchBytes();
    public static final int DISPATCH_TYPE = 4;
    public static final int DISPATCH_PAYLOAD = 8;
    public static final int DISPATCH_SIZE = 16;
    public static final int PERSONA_ID = 0;
    public static final int PERSONA_FLAGS = 8;
    public static final int AVATAR_ID = 0;
    public static final int ERROR_BYTES = 1024;
    public static final int PERSONA_MIN_BYTES = 12;
    public static final int AVATAR_MIN_BYTES = 20;
    public static final int DEBUG_STRING_BYTES = 128;

    private SteamOffsets() {}

    public static void validateLayout() {
        if (ADDRESS.byteSize() != 8)
            throw new UnsupportedOperationException("Steam ABI requires 64-bit pointers");
    }

    public static final class SteamNetworkingIdentity {
        public final byte[] m_data = new byte[IDENTITY_DATA_BYTES];
    }

    public static final class SteamNetConnectionInfo {
        public final SteamNetworkingIdentity m_identityRemote = new SteamNetworkingIdentity();
        public int m_hListenSocket, m_idPOPRemote, m_idPOPRelay, m_eState, m_eEndReason, m_nFlags;
        public byte[] m_szEndDebug = new byte[128];
        public byte[] m_szConnectionDescription = new byte[128];
    }

    public static final class SteamNetConnectionStatusChangedCallback {
        public int m_hConn, m_eOldState;
        public SteamNetConnectionInfo m_info;
    }

    public static SteamNetConnectionInfo readInfo(MemorySegment memory) {
        SteamNetConnectionInfo info = new SteamNetConnectionInfo();
        MemorySegment.copy(
                memory,
                JAVA_BYTE,
                INFO_IDENTITY + IDENTITY_DATA,
                info.m_identityRemote.m_data,
                0,
                IDENTITY_DATA_BYTES);
        info.m_hListenSocket = memory.get(JAVA_INT, INFO_LISTEN);
        info.m_idPOPRemote = memory.get(JAVA_INT, INFO_POP_REMOTE);
        info.m_idPOPRelay = memory.get(JAVA_INT, INFO_POP_RELAY);
        info.m_eState = memory.get(JAVA_INT, INFO_STATE);
        info.m_eEndReason = memory.get(JAVA_INT, INFO_END_REASON);
        info.m_szEndDebug = memory.asSlice(INFO_END_DEBUG, DEBUG_STRING_BYTES).toArray(JAVA_BYTE);
        info.m_szConnectionDescription =
                memory.asSlice(INFO_DESCRIPTION, DEBUG_STRING_BYTES).toArray(JAVA_BYTE);
        info.m_nFlags = memory.get(JAVA_INT, INFO_FLAGS);
        return info;
    }

    public static SteamNetConnectionStatusChangedCallback readCallback(MemorySegment pointer) {
        return readCallback(pointer, PLATFORM);
    }

    public static SteamNetConnectionStatusChangedCallback readCallback(
            MemorySegment pointer, SteamPlatform platform) {
        MemorySegment memory = pointer.reinterpret(platform.callbackBytes());
        SteamNetConnectionStatusChangedCallback event =
                new SteamNetConnectionStatusChangedCallback();
        event.m_hConn = memory.get(JAVA_INT, CALLBACK_CONNECTION);
        event.m_info = readInfo(memory.asSlice(platform.callbackInfo(), CONNECTION_INFO_BYTES));
        event.m_eOldState = memory.get(JAVA_INT, platform.callbackOldState());
        return event;
    }
}
