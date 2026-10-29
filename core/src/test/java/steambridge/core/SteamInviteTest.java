package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class SteamInviteTest {
    private static final long HOST = 76561198000000001L;

    @Test
    public void sessionRoundTripsAndEachWorldOpeningHasANewToken() {
        String first = SteamInvite.create(HOST, 7);
        SteamInvite invite = SteamInvite.parse(first);
        assertNotNull(invite);
        assertTrue(invite.matches(HOST, 7, first));
        assertFalse(invite.matches(HOST, 7, SteamInvite.create(HOST, 7)));
    }

    @Test
    public void senderPortAndCurrentPresenceMustAllMatch() {
        String value = SteamInvite.create(HOST, 65534);
        SteamInvite invite = SteamInvite.parse(value);
        assertFalse(invite.matches(HOST + 1, 65534, value));
        assertFalse(invite.matches(HOST, 0, value));
        assertFalse(invite.matches(HOST, 65534, ""));
        assertFalse(invite.matches(HOST, 65534, null));
    }

    @Test
    public void unrelatedAndMalformedConnectStringsAreIgnored() {
        for (String value :
                new String[] {
                    null,
                    "",
                    "connect 127.0.0.1",
                    "sbm:2:hello",
                    "sbm:1:0:0:abc",
                    "sbm:1:" + HOST + ":65535:0123456789abcdef0123456789abcdef",
                    "sbm:1:" + HOST + ":-1:0123456789abcdef0123456789abcdef",
                    SteamInvite.create(HOST, 0) + "\n"
                }) assertNull(value, SteamInvite.parse(value));
    }

    @Test
    public void invalidPublicationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SteamInvite.create(0, 1));
        assertThrows(IllegalArgumentException.class, () -> SteamInvite.create(HOST, -1));
    }
}
