package steambridge.steam;

import com.codedisaster.steamworks.SteamLibraryLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.core.LinuxNativeRuntime;

import java.io.InputStream;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SteamNativeLibraries implements SteamLibraryLoader {
    private static final Logger LOG = LogManager.getLogger("steambridge");
    private static final Map<String, Path> loaded = new ConcurrentHashMap<String, Path>();
    private static Path directory;

    static String platform(String os, String arch) {
        String cpu = arch.toLowerCase(Locale.ROOT);
        if (!cpu.equals("amd64") && !cpu.equals("x86_64"))
            throw new IllegalStateException("Steam Bridge requires x86-64 Java");
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return "windows-x64";
        if (name.contains("linux")) return "linux-x64";
        throw new IllegalStateException("Unsupported Steam Bridge platform: " + os);
    }

    static String fileName(String library, String platform) {
        if (!library.equals("steam_api") && !library.equals("steamworks4j"))
            throw new IllegalArgumentException("Unknown Steam library");
        return platform.equals("windows-x64") ? library + "64.dll" : "lib" + library + ".so";
    }

    @Override
    public boolean loadLibrary(String library) {
        synchronized (SteamNativeLibraries.class) {
            if (loaded.containsKey(library)) return true;
            try {
                String platform =
                        platform(
                                System.getProperty("os.name", ""),
                                System.getProperty("os.arch", ""));
                String fileName = fileName(library, platform);
                if (platform.equals("linux-x64")) {
                    Path path = LinuxNativeRuntime.extract(library);
                    System.load(path.toAbsolutePath().toString());
                    loaded.put(library, path);
                    return true;
                }
                if (directory == null) {
                    directory = Files.createTempDirectory("steambridge-native-");
                    directory.toFile().deleteOnExit();
                }
                Path target = directory.resolve(fileName);
                try (InputStream stream =
                        SteamNativeLibraries.class.getResourceAsStream("/" + fileName)) {
                    if (stream == null)
                        throw new IllegalStateException(
                                "Missing " + fileName + "; install the " + platform + " build");
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                }
                target.toFile().deleteOnExit();
                System.load(target.toAbsolutePath().toString());
                loaded.put(library, target);
                return true;
            } catch (Throwable error) {
                LOG.error("Steam native library {} failed to load", library, error);
                return false;
            }
        }
    }

    static String loadedSteamApi() {
        Path path = loaded.get("steam_api");
        return path == null ? null : path.toAbsolutePath().toString();
    }
}
