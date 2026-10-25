package steambridge.core;

public final class ReceiveBackoff {
    private int delay = 1;

    public int afterPoll(boolean received) {
        if (received) {
            reset();
            return 0;
        }
        int current = delay;
        delay = Math.min(4, delay * 2);
        return current;
    }

    public void reset() {
        delay = 1;
    }
}
