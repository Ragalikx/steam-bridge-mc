package steambridge.panama;

public final class SteamID {
    private final long value;

    private SteamID(long value) {
        this.value = value;
    }

    public static SteamID createFromNativeHandle(long value) {
        return new SteamID(value);
    }

    public long value() {
        return value;
    }

    public boolean isValid() {
        return value != 0;
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SteamID id && value == id.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }
}
