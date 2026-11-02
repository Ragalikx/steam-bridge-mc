package steambridge.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class LinuxNativeRuntime {
    public interface Resources {
        InputStream open(String name) throws IOException;
    }

    private static final LinuxNativeRuntime INSTANCE = new LinuxNativeRuntime();
    private static final String[] NAMES = {"libsteam_api.so", "libsteamworks4j.so"};
    private static final String[] HASHES = {
        "0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc",
        "4d255292c9ee257087053f8b1c0f0ab5a8f831193d3e6f37a10e5c046d6e1886"
    };
    private volatile Resources resources;

    LinuxNativeRuntime() {}

    public static void register(int api, Resources source) {
        INSTANCE.install(api, source);
    }

    synchronized void install(int api, Resources source) {
        if (api != 1)
            throw new IllegalArgumentException("Incompatible SBM Linux Runtime API: " + api);
        Objects.requireNonNull(source, "Linux native resources");
        if (resources != null)
            throw new IllegalStateException("SBM Linux Runtime is already registered");
        resources = source;
    }

    public static Path extract(String library) throws IOException {
        return INSTANCE.resolve(
                library,
                Paths.get(System.getProperty("user.home"), ".cache", "steambridge", "native"));
    }

    synchronized Path resolve(String library, Path cache) throws IOException {
        int selected;
        if (library.equals("steam_api")) selected = 0;
        else if (library.equals("steamworks4j")) selected = 1;
        else throw new IllegalArgumentException("Unknown Steam library: " + library);
        Resources source = resources;
        if (source == null)
            throw new IllegalStateException(
                    "Install SBM Linux Runtime matching this Minecraft/Java version");
        byte[][] payloads = new byte[2][];
        for (int i = 0; i < NAMES.length; i++) {
            try (InputStream stream = source.open(NAMES[i])) {
                if (stream == null) throw new IOException("Missing Linux native: " + NAMES[i]);
                payloads[i] = read(stream);
            }
            if (!hash(payloads[i]).equals(HASHES[i]))
                throw new SecurityException("Steam Linux resource checksum mismatch: " + NAMES[i]);
        }
        Path directory =
                cache.resolve(
                        "jna-"
                                + hash(
                                        (HASHES[0] + HASHES[1])
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets
                                                                .US_ASCII)));
        Files.createDirectories(directory);
        for (int i = 0; i < NAMES.length; i++) {
            Path target = directory.resolve(NAMES[i]);
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(directory, "steam-", ".tmp");
                try {
                    Files.write(temporary, payloads[i]);
                    try {
                        try {
                            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException unsupported) {
                            Files.move(temporary, target);
                        }
                    } catch (IOException race) {
                        if (!Files.isRegularFile(target)) throw race;
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            try (InputStream cached = Files.newInputStream(target)) {
                if (!hash(read(cached)).equals(HASHES[i]))
                    throw new SecurityException("Steam Linux cache checksum mismatch: " + target
                            + "; delete this file to let SBM extract it again");
            }
        }
        return directory.resolve(NAMES[selected]);
    }

    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > 16 * 1024 * 1024)
                throw new IOException("Steam native resource is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest)
                result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
