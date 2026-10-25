package steambridge.steam;

import com.codedisaster.steamworks.SteamAPI;
import com.sun.jna.NativeLibrary;
import java.lang.reflect.Method;

public final class NativeLinkSmoke {
    public static void main(String[] arguments) {
        SteamOffsets.validateLayout();
        try (NativeAllocation allocation = new NativeAllocation(4)) {
            allocation.buffer().setInt(0, 42);
            if (allocation.buffer().getInt(0) != 42) throw new IllegalStateException("Native allocation failed");
        }
        if (!SteamAPI.loadLibraries(new SteamNativeLibraries())) throw new IllegalStateException("Native libraries failed to load");
        NativeLibrary library = NativeLibrary.getInstance(SteamNativeLibraries.loadedSteamApi());
        int functions = 0;
        for (Method method : SteamSocketsApi.FlatApi.class.getDeclaredMethods()) {
            library.getFunction(method.getName());
            functions++;
        }
        System.out.println("Native linkage OK: " + functions + " Steam exports");
    }
}
