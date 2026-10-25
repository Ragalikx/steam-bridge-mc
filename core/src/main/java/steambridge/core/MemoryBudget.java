package steambridge.core;

import java.util.concurrent.atomic.AtomicLong;

public final class MemoryBudget {
    private final long limit;
    private final AtomicLong used = new AtomicLong();

    public MemoryBudget(long limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit");
        this.limit = limit;
    }

    public boolean reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        long current;
        do {
            current = used.get();
            if (bytes > limit - current) return false;
        } while (!used.compareAndSet(current, current + bytes));
        return true;
    }

    public void release(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        long current;
        do {
            current = used.get();
            if (bytes > current) throw new IllegalStateException("Unowned memory released");
        } while (!used.compareAndSet(current, current - bytes));
    }

    public long available() {
        return limit - used.get();
    }

    public long used() {
        return used.get();
    }
}
