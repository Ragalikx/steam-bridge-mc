package steambridge.panama;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.*;
import java.nio.ByteBuffer;

public final class SteamUtils {
    private final MemorySegment handle = SteamAPI.requireInterface("SteamAPI_SteamUtils_v010");

    public boolean getImageSize(int image, int[] dimensions) {
        if (dimensions.length < 2) throw new IllegalArgumentException("Image dimensions required");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment width = arena.allocate(JAVA_INT), height = arena.allocate(JAVA_INT);
            if ((byte)
                            SteamAPI.call(
                                    "SteamAPI_ISteamUtils_GetImageSize",
                                    JAVA_BYTE,
                                    handle,
                                    image,
                                    width,
                                    height)
                    == 0) return false;
            dimensions[0] = width.get(JAVA_INT, 0);
            dimensions[1] = height.get(JAVA_INT, 0);
            return true;
        }
    }

    public boolean getImageRGBA(int image, ByteBuffer output) {
        if (!output.isDirect()) throw new IllegalArgumentException("Native image buffer required");
        return (byte)
                        SteamAPI.call(
                                "SteamAPI_ISteamUtils_GetImageRGBA",
                                JAVA_BYTE,
                                handle,
                                image,
                                MemorySegment.ofBuffer(output),
                                output.remaining())
                != 0;
    }

    public void dispose() {}
}
