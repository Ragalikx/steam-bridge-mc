package steambridge.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class LogLimiter {
    private final int capacity;
    private final long intervalNanos;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    public LogLimiter(int capacity, long intervalMillis) {
        this(capacity, intervalMillis, System::nanoTime);
    }

    public LogLimiter(int capacity, long intervalMillis, LongSupplier clock) {
        if (capacity <= 0
                || intervalMillis <= 0
                || intervalMillis > Long.MAX_VALUE / 1_000_000
                || clock == null) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.intervalNanos = intervalMillis * 1_000_000;
        this.clock = clock;
    }

    /** Returns suppressed repeats, or -1 when this occurrence should remain silent. */
    public synchronized long acquire(String key) {
        if (key == null) throw new NullPointerException("key");
        long now = clock.getAsLong();
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() == capacity) windows.remove(windows.keySet().iterator().next());
            windows.put(key, new Window(now));
            return 0;
        }
        if (now - window.startedAt < intervalNanos) {
            if (window.suppressed < Long.MAX_VALUE) window.suppressed++;
            return -1;
        }
        long suppressed = window.suppressed;
        window.startedAt = now;
        window.suppressed = 0;
        return suppressed;
    }

    private static final class Window {
        long startedAt;
        long suppressed;

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
