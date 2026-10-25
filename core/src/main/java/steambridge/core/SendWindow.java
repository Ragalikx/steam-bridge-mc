package steambridge.core;

public final class SendWindow {
    private SendQueueState state = SendQueueState.UNKNOWN;
    private boolean sampled;
    private long sampledAt;
    private long submittedBytes;

    public boolean pollDue(long now) {
        return !sampled || now - sampledAt >= TransportPolicy.QUEUE_POLL_NANOS;
    }

    public void update(SendQueueState state, long now) {
        this.state = state == null ? SendQueueState.UNKNOWN : state;
        sampled = true;
        sampledAt = now;
        submittedBytes = 0;
    }

    public boolean permits(int length) {
        if (length <= 0 || length > QueueLimits.SEND_CHUNK_BYTES)
            throw new IllegalArgumentException("Invalid send length");
        if (!state.known) return true;
        long pending = state.pendingReliableBytes + submittedBytes;
        return (pending == 0 || state.queueMicros < TransportPolicy.MAX_QUEUE_MICROS)
                && pending <= (long) state.byteWindow() - length;
    }

    public int limitLength(int maximum) {
        return state.known ? Math.min(maximum, state.byteWindow()) : maximum;
    }

    public void submitted(int length) {
        submittedBytes += length;
    }
}
