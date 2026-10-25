package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NativeCallGateTest {
    @Test
    public void cleanupWaitsForExistingCallAndRejectsNewCalls() throws Exception {
        NativeCallGate gate = new NativeCallGate();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean destroyed = new AtomicBoolean();
        Thread caller =
                new Thread(
                        () -> {
                            try (NativeCallGate.Lease lease = gate.enter()) {
                                assertNotNull(lease);
                                entered.countDown();
                                release.await();
                                assertFalse(destroyed.get());
                            } catch (InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                        });
        caller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> gate.stopAndRun(() -> destroyed.set(true)));
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            NativeCallGate.Lease lease = gate.enter();
            if (lease == null) break;
            lease.close();
            Thread.sleep(1);
        }
        assertNull(gate.enter());
        assertFalse(destroyed.get());
        release.countDown();
        caller.join(2000);
        closer.join(2000);
        assertFalse(caller.isAlive());
        assertFalse(closer.isAlive());
        assertTrue(destroyed.get());
        gate.stopAndRun(() -> fail("Duplicate cleanup"));
    }

    @Test
    public void exceptionStillReleasesLease() {
        NativeCallGate gate = new NativeCallGate();
        try (NativeCallGate.Lease lease = gate.enter()) {
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException expected) {
        }
        AtomicBoolean destroyed = new AtomicBoolean();
        gate.stopAndRun(() -> destroyed.set(true));
        assertTrue(destroyed.get());
    }
}
