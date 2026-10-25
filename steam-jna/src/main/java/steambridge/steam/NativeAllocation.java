package steambridge.steam;

import com.sun.jna.Memory;

final class NativeAllocation implements AutoCloseable {
    static final class Buffer extends Memory {
        Buffer(long size) {
            super(size);
        }

        void release() {
            super.dispose();
        }
    }

    private final Buffer buffer;

    NativeAllocation(long size) {
        buffer = new Buffer(size);
    }

    Buffer buffer() {
        return buffer;
    }

    @Override
    public void close() {
        buffer.release();
    }
}
