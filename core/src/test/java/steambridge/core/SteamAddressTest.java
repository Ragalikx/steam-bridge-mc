package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class SteamAddressTest {
    @Test
    public void acceptsIndividualIdWithVanillaPortSuffix() {
        assertEquals(76561198012345678L, SteamAddress.parse(" 76561198012345678:25565 "));
    }

    @Test
    public void invalidNumericInputsDoNotInterceptVanillaConnections() {
        for (String input :
                new String[] {
                    null,
                    "localhost",
                    "[::1]:25565",
                    "765611999999999999999999",
                    "76561197960265728",
                    "99999999999999999",
                    "7656119801234567x"
                }) {
            assertEquals(0L, SteamAddress.parse(input));
        }
    }
}
