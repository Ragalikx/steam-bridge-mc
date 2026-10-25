package steambridge.steam;

import static org.junit.Assert.*;

import static java.lang.foreign.ValueLayout.*;

import org.junit.Test;

import java.lang.foreign.*;
import java.util.ArrayList;

public class SteamMessageBatchTest {
    @Test
    public void duplicatePayloadIsRejectedAndReleasedOnlyOnce() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(16, 8);
            MemorySegment duplicate = message(arena, 1, new byte[] {1});
            pointers.setAtIndex(ADDRESS, 0, duplicate);
            pointers.setAtIndex(ADDRESS, 1, duplicate);
            var released = new ArrayList<Long>();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            SteamMessageBatch.copy(
                                    pointers, 2, 2, pointer -> released.add(pointer.address())));
            assertEquals(1, released.size());
        }
    }

    private MemorySegment message(Arena arena, int handle, byte[] data) {
        MemorySegment message = arena.allocate(SteamOffsets.MESSAGE_BYTES, 8);
        MemorySegment payload = arena.allocateFrom(JAVA_BYTE, data);
        message.set(ADDRESS, 0, payload);
        message.set(JAVA_INT, 8, data.length);
        message.set(JAVA_INT, 12, handle);
        return message;
    }

    @Test
    public void copiesPayloadBeforeNativeMessagesAreReleased() {
        SteamSocketsApi.ReceivedMessage[] result;
        var released = new ArrayList<Long>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(16, 8);
            pointers.setAtIndex(ADDRESS, 0, message(arena, 12, new byte[] {1, 2, 3}));
            pointers.setAtIndex(ADDRESS, 1, message(arena, 13, new byte[] {4}));
            result =
                    SteamMessageBatch.copy(
                            pointers, 2, 2, pointer -> released.add(pointer.address()));
        }
        assertEquals(2, released.size());
        assertEquals(12, result[0].getConnection());
        assertArrayEquals(new byte[] {1, 2, 3}, result[0].getData());
        assertArrayEquals(new byte[] {4}, result[1].getData());
    }

    @Test
    public void malformedPayloadStillReleasesTheEntireBatch() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(24, 8);
            MemorySegment invalid = message(arena, 1, new byte[] {1});
            invalid.set(JAVA_INT, 8, -1);
            pointers.setAtIndex(ADDRESS, 0, invalid);
            pointers.setAtIndex(ADDRESS, 1, message(arena, 2, new byte[] {2}));
            pointers.setAtIndex(ADDRESS, 2, message(arena, 3, new byte[] {3}));
            var released = new ArrayList<Long>();
            RuntimeException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    SteamMessageBatch.copy(
                                            pointers,
                                            3,
                                            3,
                                            pointer -> {
                                                released.add(pointer.address());
                                                if (released.size() == 1)
                                                    throw new IllegalStateException(
                                                            "Release failed");
                                            }));
            assertTrue(failure.getMessage().contains("size"));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(3, released.size());
        }
    }

    @Test
    public void invalidCountReleasesOnlyKnownSlotsAndDuplicatePointersOnlyOnce() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(16, 8);
            MemorySegment duplicate = message(arena, 1, new byte[] {1});
            pointers.setAtIndex(ADDRESS, 0, duplicate);
            pointers.setAtIndex(ADDRESS, 1, duplicate);
            var released = new ArrayList<Long>();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            SteamMessageBatch.copy(
                                    pointers, 100, 2, pointer -> released.add(pointer.address())));
            assertEquals(1, released.size());
        }
    }
}
