package steambridge.panama;

import static java.lang.foreign.ValueLayout.*;

public final class SteamUser {
    private final java.lang.foreign.MemorySegment handle =
            SteamAPI.requireInterface("SteamAPI_SteamUser_v023");

    public SteamID getSteamID() {
        return SteamID.createFromNativeHandle(
                (long) SteamAPI.call("SteamAPI_ISteamUser_GetSteamID", JAVA_LONG, handle));
    }

    public void dispose() {}
}
