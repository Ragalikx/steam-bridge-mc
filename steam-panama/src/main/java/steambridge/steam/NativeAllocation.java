package steambridge.steam;

import java.lang.foreign.*;

final class NativeAllocation implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment memory;

    NativeAllocation(long bytes) {
        memory = arena.allocate(bytes, 8);
    }

    MemorySegment buffer() {
        return memory;
    }

    public void close() {
        arena.close();
    }
}
