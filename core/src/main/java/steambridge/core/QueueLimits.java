package steambridge.core;

public final class QueueLimits {
    public static final int INBOUND_BYTES = 8 * 1024 * 1024;
    public static final int OUTBOUND_BYTES = 8 * 1024 * 1024;
    public static final int MAX_NATIVE_MESSAGE_BYTES = 512 * 1024;
    public static final int SEND_CHUNK_BYTES = 32 * 1024;
    public static final int SEND_BUDGET_BYTES = 256 * 1024;
    public static final long BACKPRESSURE_TIMEOUT_NANOS = 30_000_000_000L;

    private QueueLimits() {}
}
