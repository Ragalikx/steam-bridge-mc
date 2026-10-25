package steambridge.panama;

import java.util.Locale;

public enum SteamPlatform {
    WINDOWS_X64("steam_api64.dll", 712, 8, 704, 24),
    LINUX_X64("libsteam_api.so", 704, 4, 700, 20);

    private final String library;
    private final int callbackBytes, callbackInfo, callbackOldState, dispatchBytes;

    SteamPlatform(
            String library,
            int callbackBytes,
            int callbackInfo,
            int callbackOldState,
            int dispatchBytes) {
        this.library = library;
        this.callbackBytes = callbackBytes;
        this.callbackInfo = callbackInfo;
        this.callbackOldState = callbackOldState;
        this.dispatchBytes = dispatchBytes;
    }

    public static SteamPlatform current() {
        return resolve(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static SteamPlatform resolve(String os, String arch) {
        String cpu = arch.toLowerCase(Locale.ROOT);
        if (!cpu.equals("amd64") && !cpu.equals("x86_64"))
            throw new UnsupportedOperationException("Steam Bridge requires x86-64 Java");
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return WINDOWS_X64;
        if (name.equals("linux")) return LINUX_X64;
        throw new UnsupportedOperationException("Unsupported Steam Bridge OS: " + os);
    }

    public String library() {
        return library;
    }

    public int callbackBytes() {
        return callbackBytes;
    }

    public int callbackInfo() {
        return callbackInfo;
    }

    public int callbackOldState() {
        return callbackOldState;
    }

    public int dispatchBytes() {
        return dispatchBytes;
    }
}
