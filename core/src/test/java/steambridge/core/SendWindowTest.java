package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class SendWindowTest {
    @Test
    public void queueTimeAndPendingBytesBothApply() {
        SendWindow window = new SendWindow();
        window.update(new SendQueueState(true, 1, 250_000, 8 * 1024 * 1024), 0);
        assertFalse(window.permits(1));
        window.update(new SendQueueState(true, 0, 900_000, 8 * 1024 * 1024), 5_000_000);
        assertTrue(window.permits(QueueLimits.SEND_CHUNK_BYTES));
        window.update(new SendQueueState(true, 256 * 1024, 0, 8 * 1024 * 1024), 10_000_000);
        assertFalse(window.permits(1));
    }

    @Test
    public void cachedTelemetryIncludesNewlySubmittedBytes() {
        SendWindow window = new SendWindow();
        window.update(new SendQueueState(true, 0, 0, 8 * 1024 * 1024), 0);
        int length = QueueLimits.SEND_CHUNK_BYTES;
        for (int i = 0; i < TransportPolicy.MAX_PENDING_RELIABLE_BYTES / length; i++) {
            assertTrue(window.permits(length));
            window.submitted(length);
        }
        assertFalse(window.permits(1));
        assertFalse(window.pollDue(TransportPolicy.QUEUE_POLL_NANOS - 1));
        assertTrue(window.pollDue(TransportPolicy.QUEUE_POLL_NANOS));
        window.update(new SendQueueState(true, 0, 0, 8 * 1024 * 1024), 5_000_000);
        assertTrue(window.permits(length));
    }

    @Test
    public void slowRouteUsesSmallerWindowAndUnknownTelemetryDoesNotDeadlock() {
        SendWindow window = new SendWindow();
        window.update(new SendQueueState(true, 0, 0, 64 * 1024), 0);
        int chunk = window.limitLength(QueueLimits.SEND_CHUNK_BYTES);
        assertTrue(chunk < QueueLimits.SEND_CHUNK_BYTES);
        assertTrue(window.permits(chunk));
        window.submitted(chunk);
        assertFalse(window.permits(1));
        window.update(SendQueueState.UNKNOWN, 5_000_000);
        assertTrue(window.permits(QueueLimits.SEND_CHUNK_BYTES));
    }

    @Test
    public void sixtyFourPeersHaveIndependentWindows() {
        for (int player = 0; player < 64; player++) {
            SendWindow window = new SendWindow();
            window.update(
                    new SendQueueState(true, player == 0 ? 256 * 1024 : 0, 0, 8 * 1024 * 1024), 0);
            assertEquals(player != 0, window.permits(QueueLimits.SEND_CHUNK_BYTES));
        }
    }

    @Test
    public void idlePollingIsBoundedAndWorkResetsTheDelay() {
        ReceiveBackoff backoff = new ReceiveBackoff();
        assertEquals(1, backoff.afterPoll(false));
        assertEquals(2, backoff.afterPoll(false));
        assertEquals(4, backoff.afterPoll(false));
        assertEquals(4, backoff.afterPoll(false));
        assertEquals(0, backoff.afterPoll(true));
        assertEquals(1, backoff.afterPoll(false));
        backoff.reset();
        assertEquals(1, backoff.afterPoll(false));
    }
}
