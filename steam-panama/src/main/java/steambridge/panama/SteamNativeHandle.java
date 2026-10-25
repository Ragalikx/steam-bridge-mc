package steambridge.panama;

public final class SteamNativeHandle {
    private SteamNativeHandle() {}

    public static long getNativeHandle(SteamID id) {
        return id == null ? 0 : id.value();
    }
}
