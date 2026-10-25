package steambridge.steam;

import static java.lang.foreign.ValueLayout.*;

import steambridge.core.QueueLimits;

import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

final class SteamMessageBatch {
    static SteamSocketsApi.ReceivedMessage[] copy(
            MemorySegment pointers, int count, int capacity, Consumer<MemorySegment> release) {
        Throwable primary = null;
        long[] seen = new long[Math.max(0, capacity)];
        int seenCount = 0;
        try {
            if (count < 0 || count > capacity)
                throw new IllegalStateException("Invalid Steam receive count: " + count);
            var result = new SteamSocketsApi.ReceivedMessage[count];
            for (int i = 0; i < count; i++) {
                MemorySegment pointer = pointers.getAtIndex(ADDRESS, i);
                if (pointer.address() == 0) throw new IllegalStateException("Null Steam message");
                if (contains(seen, seenCount, pointer.address()))
                    throw new IllegalStateException("Duplicate Steam message");
                seen[seenCount++] = pointer.address();
                MemorySegment message = pointer.reinterpret(SteamOffsets.MESSAGE_BYTES);
                int size = message.get(JAVA_INT, SteamOffsets.MESSAGE_SIZE);
                if (size < 0 || size > QueueLimits.MAX_NATIVE_MESSAGE_BYTES)
                    throw new IllegalStateException("Invalid Steam message size");
                MemorySegment payload = message.get(ADDRESS, SteamOffsets.MESSAGE_DATA);
                if (size != 0 && payload.address() == 0)
                    throw new IllegalStateException("Null Steam payload");
                result[i] =
                        new SteamSocketsApi.ReceivedMessage(
                                message.get(JAVA_INT, SteamOffsets.MESSAGE_CONNECTION),
                                size == 0
                                        ? new byte[0]
                                        : payload.reinterpret(size).toArray(JAVA_BYTE));
            }
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable cleanup = null;
            seenCount = 0;
            for (int i = 0; i < Math.min(Math.max(count, 0), capacity); i++) {
                MemorySegment pointer = pointers.getAtIndex(ADDRESS, i);
                if (pointer.address() == 0 || contains(seen, seenCount, pointer.address()))
                    continue;
                seen[seenCount++] = pointer.address();
                try {
                    release.accept(pointer);
                } catch (Throwable failure) {
                    if (cleanup == null) cleanup = failure;
                    else if (cleanup != failure) cleanup.addSuppressed(failure);
                }
            }
            if (cleanup != null) {
                if (primary != null) {
                    if (primary != cleanup) primary.addSuppressed(cleanup);
                } else throw new IllegalStateException("Steam message release failed", cleanup);
            }
        }
    }

    private static boolean contains(long[] addresses, int size, long address) {
        for (int i = 0; i < size; i++) if (addresses[i] == address) return true;
        return false;
    }
}
