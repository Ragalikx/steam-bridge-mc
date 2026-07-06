/*
 * Copyright (c) 2019-2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * Stores defined structs/offsets specific to the current Steamworks (SDR) SDK version.
 *
 * JNA offsets and structures are highly dependent on the layout of C/C++ structs.
 * They can shift or break whenever Valve changes the internal SteamNetworkingSockets structure,
 * for example adding or removing fields between SDK versions.
 *
 * By extracting them entirely into this file, it's simpler to review and update
 * binary mappings when a new Steam SDK is adopted.
 */
public final class SteamOffsets {

    private SteamOffsets() {}

    // Precomputed struct field offsets in SteamNetworkingMessage_t (pack(8), x64).
    // Derived from steamnetworkingtypes.h. DO NOT change without verifying
    // against the exact SDK version bundled with steamworks4j.
    // Use these for GC-free zero-allocation memory reads over JNA Structure.
    public static final int MSG_OFF_PDATA   = 0;  // 8-byte pointer
    public static final int MSG_OFF_CBSIZE  = 8;  // int32
    public static final int MSG_OFF_CONN    = 12; // uint32 / HSteamNetConnection
    public static final int MSG_OFF_FLAGS   = 196; // int32
    public static final int MSG_OFF_LANE    = 208; // uint16

    /**
     * Verifies the raw offset constants above agree with the layout JNA computes for the full
     * {@link SteamNetworkingMessage} struct. If Valve reshuffles the struct (or a constant has a
     * typo) this throws, so the caller can abort cleanly instead of silently corrupting native
     * memory at runtime.
     * <p>
     * Must be called explicitly (see {@code SteamManager.init()}): the {@code MSG_OFF_*} fields are
     * compile-time constants that get inlined at their use sites, so nothing here would ever trigger
     * class initialisation on its own — a {@code static} block would be dead code.
     *
     * @throws IllegalStateException if any offset disagrees with JNA's computed layout
     */
    public static void validateLayout() {
        SteamNetworkingMessage probe = new SteamNetworkingMessage();
        checkOffset(probe, "m_pData",   MSG_OFF_PDATA);
        checkOffset(probe, "m_cbSize",  MSG_OFF_CBSIZE);
        checkOffset(probe, "m_conn",    MSG_OFF_CONN);
        checkOffset(probe, "m_nFlags",  MSG_OFF_FLAGS);
        checkOffset(probe, "m_idxLane", MSG_OFF_LANE);
    }

    private static void checkOffset(SteamNetworkingMessage probe, String field, int expected) {
        int actual = probe.offsetOf(field);
        if (actual != expected) {
            throw new IllegalStateException(
                "SteamNetworkingMessage_t layout mismatch: field '" + field + "' expected at offset "
                + expected + " but JNA computed " + actual
                + ". The bundled Steam SDK structs have changed — update SteamOffsets before use.");
        }
    }

    /**
     * Structure representing an IP Address in SteamNetworkingSockets.
     */
    public static class SteamNetworkingIPAddr extends Structure {
        public byte[] m_ipv6 = new byte[16];
        public short m_port;

        public SteamNetworkingIPAddr() {
            setAlignType(ALIGN_NONE);
        }

        public SteamNetworkingIPAddr(Pointer p) {
            super(p);
            setAlignType(ALIGN_NONE);
        }

        protected List<String> getFieldOrder() {
            return Arrays.asList("m_ipv6", "m_port");
        }
    }

    /**
     * Represents a remote peer identity (e.g. Steam ID or IP).
     */
    public static class SteamNetworkingIdentity extends Structure {
        public int m_eType;
        public int m_cbSize;
        public byte[] m_data = new byte[128];

        public SteamNetworkingIdentity() {
            setAlignType(ALIGN_NONE);
        }

        public SteamNetworkingIdentity(Pointer p) {
            super(p);
            setAlignType(ALIGN_NONE);
        }

        protected List<String> getFieldOrder() {
            return Arrays.asList("m_eType", "m_cbSize", "m_data");
        }
    }

    /**
     * Details and state info of a given Steam connection.
     */
    public static class SteamNetConnectionInfo extends Structure {
        public SteamNetworkingIdentity m_identityRemote = new SteamNetworkingIdentity();
        public long m_nUserData;
        public int m_hListenSocket;
        public SteamNetworkingIPAddr m_addrRemote = new SteamNetworkingIPAddr();
        public short m__pad1;
        public int m_idPOPRemote;
        public int m_idPOPRelay;
        public int m_eState;
        public int m_eEndReason;
        public byte[] m_szEndDebug = new byte[128];
        public byte[] m_szConnectionDescription = new byte[128];
        public int m_nFlags;
        public int[] reserved = new int[63];

        public SteamNetConnectionInfo() {}

        public SteamNetConnectionInfo(Pointer p) {
            super(p);
        }

        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "m_identityRemote", "m_nUserData", "m_hListenSocket", "m_addrRemote",
                    "m__pad1", "m_idPOPRemote", "m_idPOPRelay", "m_eState", "m_eEndReason",
                    "m_szEndDebug", "m_szConnectionDescription", "m_nFlags", "reserved"
            );
        }
    }

    /**
     * Contains live connection health, packet drops, and bandwidth status.
     */
    public static class SteamNetConnectionRealTimeStatus extends Structure {
        public int m_eState;
        public int m_nPing;
        public float m_flConnectionQualityLocal;
        public float m_flConnectionQualityRemote;
        public float m_flOutPacketsPerSec;
        public float m_flOutBytesPerSec;
        public float m_flInPacketsPerSec;
        public float m_flInBytesPerSec;
        public int m_nSendRateBytesPerSecond;
        public int m_cbPendingUnreliable;
        public int m_cbPendingReliable;
        public int m_cbSentUnackedReliable;
        public long m_usecQueueTime;
        public int[] reserved = new int[16];

        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "m_eState", "m_nPing", "m_flConnectionQualityLocal", "m_flConnectionQualityRemote",
                    "m_flOutPacketsPerSec", "m_flOutBytesPerSec", "m_flInPacketsPerSec", "m_flInBytesPerSec",
                    "m_nSendRateBytesPerSecond", "m_cbPendingUnreliable", "m_cbPendingReliable",
                    "m_cbSentUnackedReliable", "m_usecQueueTime", "reserved"
            );
        }
    }

    /**
     * Wrapper for a callback triggered on socket state changes.
     */
    public static class SteamNetConnectionStatusChangedCallback extends Structure {
        public int m_hConn;
        public SteamNetConnectionInfo m_info = new SteamNetConnectionInfo();
        public int m_eOldState;

        public SteamNetConnectionStatusChangedCallback() {}

        public SteamNetConnectionStatusChangedCallback(Pointer p) {
            super(p);
        }

        protected List<String> getFieldOrder() {
            return Arrays.asList("m_hConn", "m_info", "m_eOldState");
        }
    }

    /**
     * Incoming memory page block defining an actual received packet.
     */
    public static class SteamNetworkingMessage extends Structure {
        public Pointer m_pData;
        public int m_cbSize;
        public int m_conn;
        public SteamNetworkingIdentity m_identityPeer = new SteamNetworkingIdentity();
        public long m_nConnUserData;
        public long m_usecTimeReceived;
        public long m_nMessageNumber;
        public Pointer m_pfnFreeData;
        public Pointer m_pfnRelease;
        public int m_nChannel;
        public int m_nFlags;
        public long m_nUserData;
        public short m_idxLane;
        public short m__pad1__;

        public SteamNetworkingMessage() {}

        public SteamNetworkingMessage(Pointer p) {
            super(p);
        }

        /** Exposes the protected {@link Structure#fieldOffset} for the offset self-check above. */
        int offsetOf(String field) {
            return fieldOffset(field);
        }

        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "m_pData", "m_cbSize", "m_conn", "m_identityPeer", "m_nConnUserData",
                    "m_usecTimeReceived", "m_nMessageNumber", "m_pfnFreeData", "m_pfnRelease",
                    "m_nChannel", "m_nFlags", "m_nUserData", "m_idxLane", "m__pad1__"
            );
        }
    }
}

