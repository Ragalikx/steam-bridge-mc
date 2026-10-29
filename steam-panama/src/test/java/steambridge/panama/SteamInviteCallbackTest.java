package steambridge.panama;

import static org.junit.Assert.*;

import static java.lang.foreign.ValueLayout.*;

import org.junit.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

public class SteamInviteCallbackTest {
    @Test
    public void inviteCopiesTheBoundedStringBeforeCallbackMemoryIsFreed() {
        AtomicReference<String> received = new AtomicReference<>();
        SteamAPI.setInviteListeners((id, text) -> received.set(id + ":" + text), (id, app) -> {});
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment payload = arena.allocate(264, 8);
                payload.set(JAVA_LONG, 0, 76561198000000001L);
                payload.asSlice(8, 256).setString(0, "sbm:test");
                SteamAPI.dispatchCallback(337, payload, 264);
            }
            assertEquals("76561198000000001:sbm:test", received.get());
        } finally {
            SteamAPI.setInviteListeners((id, text) -> {}, (id, app) -> {});
        }
    }

    @Test
    public void truncatedOrUnterminatedInvitesNeverReachTheConsumer() {
        SteamAPI.setInviteListeners(
                (id, text) -> fail("Invalid callback forwarded"), (id, app) -> {});
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(264, 8).fill((byte) 65);
            SteamAPI.dispatchCallback(337, payload, 263);
            SteamAPI.dispatchCallback(337, payload, 264);
            SteamAPI.dispatchCallback(337, MemorySegment.NULL, 264);
        } finally {
            SteamAPI.setInviteListeners((id, text) -> {}, (id, app) -> {});
        }
    }

    @Test
    public void presenceCallbackCarriesTheSenderAndAppId() {
        AtomicReference<String> received = new AtomicReference<>();
        SteamAPI.setInviteListeners((id, text) -> {}, (id, app) -> received.set(id + ":" + app));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(16, 8);
            payload.set(JAVA_LONG, 0, 76561198000000001L);
            payload.set(JAVA_INT, 8, 480);
            SteamAPI.dispatchCallback(336, payload, 11);
            assertNull(received.get());
            SteamAPI.dispatchCallback(336, payload, 12);
            assertEquals("76561198000000001:480", received.get());
        } finally {
            SteamAPI.setInviteListeners((id, text) -> {}, (id, app) -> {});
        }
    }
}
