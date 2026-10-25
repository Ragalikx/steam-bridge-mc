package steambridge.panama;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

public final class NativeRuntime {
    public static final int LINUX_API = 1;
    private static final String WINDOWS_SHA256 =
            "e082bf5c9f881c822b1540a76b74f9d15e18019a73ebd206a559595badcb7f65";
    private static final String LINUX_SHA256 =
            "0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc";
    private static final NativeRuntime INSTANCE = new NativeRuntime();
    private volatile Supplier<InputStream> linux;

    NativeRuntime() {}

    public static void registerLinux(int api, Supplier<InputStream> resource) {
        INSTANCE.install(api, resource);
    }

    synchronized void install(int api, Supplier<InputStream> resource) {
        if (api != LINUX_API)
            throw new IllegalArgumentException("Incompatible SBM Linux Runtime API: " + api);
        Objects.requireNonNull(resource, "Linux native resource");
        if (linux != null)
            throw new IllegalStateException("SBM Linux Runtime is already registered");
        linux = resource;
    }

    public static Path extract() throws IOException {
        return INSTANCE.resolve(
                SteamPlatform.current(),
                Path.of(System.getProperty("user.home"), ".cache", "steambridge", "native"),
                () -> NativeRuntime.class.getResourceAsStream("/steam_api64.dll"));
    }

    Path resolve(SteamPlatform platform, Path cache, Supplier<InputStream> windows)
            throws IOException {
        Supplier<InputStream> source = platform == SteamPlatform.WINDOWS_X64 ? windows : linux;
        if (source == null)
            throw new IllegalStateException(
                    "Install SBM Linux Runtime for NeoForge 26.x (Linux x64)");
        String expected = platform == SteamPlatform.WINDOWS_X64 ? WINDOWS_SHA256 : LINUX_SHA256;
        byte[] bytes;
        try (InputStream input = source.get()) {
            if (input == null)
                throw new IOException("Missing " + platform.library() + " in SBM native resources");
            bytes = input.readNBytes(16 * 1024 * 1024 + 1);
        }
        if (!digest(bytes).equals(expected))
            throw new SecurityException(
                    "Steam native resource checksum mismatch: " + platform.library());
        Path directory = cache.resolve(expected);
        Files.createDirectories(directory);
        Path target = directory.resolve(platform.library());
        if (!Files.exists(target)) {
            Path temporary = Files.createTempFile(directory, "steam-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, target);
                    }
                } catch (IOException moveFailure) {
                    if (!Files.isRegularFile(target)) throw moveFailure;
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        try (InputStream cached = Files.newInputStream(target)) {
            if (!digest(cached.readNBytes(bytes.length + 1)).equals(expected))
                throw new SecurityException("Steam native cache checksum mismatch: " + target);
        }
        return target;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
