package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConnectionRegistryTest {
    @Test
    public void retiredVoiceCannotClaimNewGame() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Object oldVoice = new Object();
        Object newGame = new Object();
        registry.register(1985733375, oldVoice, ConnectionRegistry.Kind.UDP);
        registry.remove(1985733375, oldVoice);
        registry.register(325982718, newGame, ConnectionRegistry.Kind.GAME);
        assertFalse(registry.owns(1985733375, newGame));
        assertFalse(registry.remove(325982718, oldVoice));
        assertTrue(registry.owns(325982718, newGame));
        assertEquals(ConnectionRegistry.Kind.GAME, registry.get(325982718).kind());
    }

    @Test
    public void repeatedSessionsLeaveNoOwners() {
        ConnectionRegistry registry = new ConnectionRegistry();
        for (int i = 1; i <= 1000; i++) {
            Object game = new Object(), voice = new Object();
            registry.register(i * 2, game, ConnectionRegistry.Kind.GAME);
            registry.register(i * 2 + 1, voice, ConnectionRegistry.Kind.UDP);
            assertFalse(registry.remove(i * 2, voice));
            assertTrue(registry.remove(i * 2, game));
            assertTrue(registry.remove(i * 2 + 1, voice));
            assertEquals(0, registry.size());
        }
    }

    @Test(expected = IllegalStateException.class)
    public void liveHandleCannotBeStolen() {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.register(1, new Object(), ConnectionRegistry.Kind.GAME);
        registry.register(1, new Object(), ConnectionRegistry.Kind.UDP);
    }
}
