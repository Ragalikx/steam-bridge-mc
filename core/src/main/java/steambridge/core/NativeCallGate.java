package steambridge.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class NativeCallGate {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Lease enter() {
        if (stopping.get()) return null;
        lock.readLock().lock();
        if (stopping.get()) {
            lock.readLock().unlock();
            return null;
        }
        return new Lease();
    }

    public void stopAndRun(Runnable cleanup) {
        if (!stopping.compareAndSet(false, true)) return;
        lock.writeLock().lock();
        try {
            cleanup.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public final class Lease implements AutoCloseable {
        private boolean closed;

        private Lease() {}

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.readLock().unlock();
            }
        }
    }
}
