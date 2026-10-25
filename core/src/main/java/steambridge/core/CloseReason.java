package steambridge.core;

public enum CloseReason {
    CANCELLED,
    LOCAL_TCP,
    NATIVE_RECEIVE,
    NATIVE_SEND,
    QUEUE_LIMIT,
    DRAIN_TIMEOUT,
    OTHER;

    public static CloseReason classify(String reason) {
        if (reason == null) return OTHER;
        if (reason.startsWith("Connection cancelled")) return CANCELLED;
        if (reason.startsWith("Local TCP")) return LOCAL_TCP;
        if (reason.startsWith("Native receive") || reason.startsWith("Peer receive"))
            return NATIVE_RECEIVE;
        if (reason.startsWith("Steam send")) return NATIVE_SEND;
        if (reason.contains("queue limit")) return QUEUE_LIMIT;
        if (reason.startsWith("Drain timeout")) return DRAIN_TIMEOUT;
        return OTHER;
    }
}
