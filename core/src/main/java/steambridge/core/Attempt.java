package steambridge.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class Attempt {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final long id = NEXT_ID.incrementAndGet();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public long id() {
        return id;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
