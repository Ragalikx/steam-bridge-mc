package steambridge.core;

public final class TransportPolicy {
    public static final int NATIVE_SEND_BUFFER_BYTES = 2 * 1024 * 1024;
    public static final int SEND_RATE_MAX_BYTES = 8 * 1024 * 1024;
    public static final int SEND_RATE_MAX_CONFIG = 11;
    public static final int MAX_PENDING_RELIABLE_BYTES = 256 * 1024;
    public static final long MAX_QUEUE_MICROS = 250_000;
    public static final long QUEUE_POLL_NANOS = 5_000_000;

    private TransportPolicy() {}
}
