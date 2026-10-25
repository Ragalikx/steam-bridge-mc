package steambridge.steam;

import static org.junit.Assert.*;

import org.junit.Test;

public class SteamCallbackTest {
    @Test
    public void terminalEventWinsOverCachedConnectedSnapshot() {
        SteamConnectionStatus cached =
                new SteamConnectionStatus(
                        1,
                        7,
                        true,
                        true,
                        false,
                        true,
                        SteamSocketsApi.STATE_CONNECTED,
                        0,
                        "",
                        0,
                        0,
                        20,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "");
        SteamOffsets.SteamNetConnectionStatusChangedCallback event =
                new SteamOffsets.SteamNetConnectionStatusChangedCallback();
        event.m_info.m_eState = SteamSocketsApi.STATE_CLOSED_BY_PEER;
        event.m_info.m_eEndReason = 1000;
        byte[] text = "Host stopped".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(text, 0, event.m_info.m_szEndDebug, 0, text.length);
        SteamConnectionStatus actual = SteamConnectionStatus.fromCallback(7, 1, event, cached);
        assertTrue(actual.isTerminal());
        assertFalse(actual.isConnectionActive());
        assertEquals("Host stopped", actual.getLastError());
        assertEquals(7, actual.getConnectionHandle());
    }

    @Test
    public void linuxCallbackUsesValvePackFourLayout() {
        PackedCallback callback = new PackedCallback();
        assertEquals(704, callback.size());
        assertEquals(4, callback.offset("m_info"));
        assertEquals(700, callback.offset("m_eOldState"));
        callback.m_hConn = 17;
        callback.m_info.m_eState = SteamSocketsApi.STATE_CONNECTED;
        callback.write();
        assertEquals(17, callback.getPointer().getInt(0));
        callback.m_hConn = 0;
        callback.read();
        assertEquals(17, callback.m_hConn);
        assertEquals(SteamSocketsApi.STATE_CONNECTED, callback.m_info.m_eState);
    }

    public static class PackedCallback
            extends SteamOffsets.SteamNetConnectionStatusChangedCallback {
        @Override
        protected int getNativeAlignment(Class<?> type, Object value, boolean first) {
            return Math.min(4, super.getNativeAlignment(type, value, first));
        }

        int offset(String field) {
            return fieldOffset(field);
        }
    }
}
