package steambridge.core;

public final class SendQueueState {
    public static final SendQueueState UNKNOWN = new SendQueueState(false, 0, 0, 0);

    public final boolean known;
    public final int pendingReliableBytes;
    public final long queueMicros;
    public final int sendRateBytes;

    public SendQueueState(boolean known, int pending, long queueMicros, int sendRate) {
        this.known = known;
        this.pendingReliableBytes = Math.max(0, pending);
        this.queueMicros = Math.max(0, queueMicros);
        this.sendRateBytes = Math.max(0, sendRate);
    }

    public int byteWindow() {
        int target =
                sendRateBytes == 0
                        ? TransportPolicy.MAX_PENDING_RELIABLE_BYTES
                        : sendRateBytes / 10;
        return Math.max(4096, Math.min(TransportPolicy.MAX_PENDING_RELIABLE_BYTES, target));
    }
}
