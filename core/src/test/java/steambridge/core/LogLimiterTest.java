package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class LogLimiterTest {
    @Test
    public void firstFailureIsVisibleAndRepeatsAreCountedAtBoundary() {
        AtomicLong now = new AtomicLong();
        LogLimiter limiter = new LogLimiter(2, 30_000, now::get);
        assertEquals(0, limiter.acquire("receive"));
        assertEquals(-1, limiter.acquire("receive"));
        now.set(29_999_999_999L);
        assertEquals(-1, limiter.acquire("receive"));
        now.incrementAndGet();
        assertEquals(2, limiter.acquire("receive"));
        now.addAndGet(30_000_000_000L);
        assertEquals(0, limiter.acquire("receive"));
    }

    @Test
    public void independentFailuresAndInstancesHaveIndependentWindows() {
        LogLimiter first = new LogLimiter(2, 1000, () -> 0);
        LogLimiter second = new LogLimiter(2, 1000, () -> 0);
        assertEquals(0, first.acquire("send"));
        assertEquals(0, first.acquire("receive"));
        assertEquals(-1, first.acquire("send"));
        assertEquals(0, second.acquire("send"));
    }

    @Test
    public void keyChurnEvictsOnlyTheLeastRecentlyUsedWindow() {
        LogLimiter limiter = new LogLimiter(2, 1000, () -> 0);
        assertEquals(0, limiter.acquire("a"));
        assertEquals(0, limiter.acquire("b"));
        assertEquals(-1, limiter.acquire("a"));
        assertEquals(0, limiter.acquire("c"));
        assertEquals(-1, limiter.acquire("a"));
        assertEquals(0, limiter.acquire("b"));
    }

    @Test
    public void concurrentFailuresEmitOnceAndPreserveTheSuppressedCount() throws Exception {
        AtomicLong now = new AtomicLong();
        LogLimiter limiter = new LogLimiter(2, 1000, now::get);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<Long>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++)
                results.add(executor.submit(() -> limiter.acquire("voice")));
            int emitted = 0;
            for (Future<Long> result : results) if (result.get() >= 0) emitted++;
            assertEquals(1, emitted);
            now.set(1_000_000_000L);
            assertEquals(99, limiter.acquire("voice"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void monotonicClockWrapDoesNotResetTheWindowEarly() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 500_000L);
        LogLimiter limiter = new LogLimiter(1, 1, now::get);
        assertEquals(0, limiter.acquire("receive"));
        now.addAndGet(999_999L);
        assertEquals(-1, limiter.acquire("receive"));
        now.incrementAndGet();
        assertEquals(1, limiter.acquire("receive"));
    }
}
