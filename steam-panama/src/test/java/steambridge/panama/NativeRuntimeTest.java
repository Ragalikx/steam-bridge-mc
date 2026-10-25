package steambridge.panama;

import static org.junit.Assert.*;

import static java.lang.foreign.ValueLayout.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import steambridge.steam.SteamOffsets;

import java.io.*;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class NativeRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private byte[] fixture(String os) throws IOException {
        return Files.readAllBytes(
                Path.of(System.getProperty("steambridge.test." + os + "Library")));
    }

    private Supplier<InputStream> source(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    private Supplier<InputStream> forbidden() {
        return () -> {
            throw new AssertionError("Native source for the other OS was consulted");
        };
    }

    @Test
    public void windowsUsesItsDllEvenWithLinuxRuntimeInstalled() throws Exception {
        NativeRuntime runtime = new NativeRuntime();
        runtime.install(1, forbidden());
        byte[] windows = fixture("windows");
        Path result =
                runtime.resolve(
                        SteamPlatform.WINDOWS_X64, temporary.getRoot().toPath(), source(windows));
        assertEquals("steam_api64.dll", result.getFileName().toString());
        assertArrayEquals(windows, Files.readAllBytes(result));
        assertThrows(
                SecurityException.class,
                () ->
                        runtime.resolve(
                                SteamPlatform.WINDOWS_X64,
                                temporary.getRoot().toPath(),
                                source(new byte[] {0})));
    }

    @Test
    public void linuxWithoutAddonReportsTheMissingRuntimeBeforeExtractingAnything() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new NativeRuntime()
                                        .resolve(
                                                SteamPlatform.LINUX_X64,
                                                temporary.getRoot().toPath(),
                                                forbidden()));
        assertTrue(error.getMessage().contains("Install SBM Linux Runtime"));
        assertEquals(0, Objects.requireNonNull(temporary.getRoot().list()).length);
    }

    @Test
    public void linuxProviderExtractsItsPinnedElfAndReusesTheCache() throws Exception {
        NativeRuntime runtime = new NativeRuntime();
        byte[] linux = fixture("linux");
        runtime.install(1, source(linux));
        Path first =
                runtime.resolve(SteamPlatform.LINUX_X64, temporary.getRoot().toPath(), forbidden());
        assertEquals("libsteam_api.so", first.getFileName().toString());
        assertArrayEquals(linux, Files.readAllBytes(first));
        assertEquals(
                first,
                runtime.resolve(
                        SteamPlatform.LINUX_X64, temporary.getRoot().toPath(), forbidden()));
    }

    @Test
    public void incompatibleOrDuplicateProvidersAreRejected() {
        NativeRuntime runtime = new NativeRuntime();
        assertThrows(IllegalArgumentException.class, () -> runtime.install(2, forbidden()));
        assertThrows(NullPointerException.class, () -> runtime.install(1, null));
        runtime.install(1, forbidden());
        assertThrows(IllegalStateException.class, () -> runtime.install(1, forbidden()));
    }

    @Test
    public void incorrectNativeResourcesAreRejectedBeforeTheyReachTheCache() {
        NativeRuntime runtime = new NativeRuntime();
        runtime.install(1, source(new byte[] {1, 2, 3}));
        assertThrows(
                SecurityException.class,
                () ->
                        runtime.resolve(
                                SteamPlatform.LINUX_X64,
                                temporary.getRoot().toPath(),
                                forbidden()));
        assertEquals(0, Objects.requireNonNull(temporary.getRoot().list()).length);
        assertThrows(
                SecurityException.class,
                () ->
                        runtime.resolve(
                                SteamPlatform.WINDOWS_X64,
                                temporary.getRoot().toPath(),
                                source(new byte[] {0})));
    }

    @Test
    public void corruptCacheIsRejectedWithoutOverwritingIt() throws Exception {
        NativeRuntime runtime = new NativeRuntime();
        runtime.install(1, source(fixture("linux")));
        Path path =
                runtime.resolve(SteamPlatform.LINUX_X64, temporary.getRoot().toPath(), forbidden());
        Files.write(path, new byte[] {0});
        assertThrows(
                SecurityException.class,
                () ->
                        runtime.resolve(
                                SteamPlatform.LINUX_X64,
                                temporary.getRoot().toPath(),
                                forbidden()));
        assertArrayEquals(new byte[] {0}, Files.readAllBytes(path));
    }

    @Test
    public void concurrentExtractionLeavesOneVerifiedLibraryAndNoTemporaryFiles() throws Exception {
        NativeRuntime runtime = new NativeRuntime();
        byte[] linux = fixture("linux");
        runtime.install(1, source(linux));
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<Path>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++)
                tasks.add(
                        workers.submit(
                                () ->
                                        runtime.resolve(
                                                SteamPlatform.LINUX_X64,
                                                temporary.getRoot().toPath(),
                                                forbidden())));
            Path first = tasks.getFirst().get(10, TimeUnit.SECONDS);
            for (Future<Path> task : tasks) assertEquals(first, task.get(10, TimeUnit.SECONDS));
            assertArrayEquals(linux, Files.readAllBytes(first));
            try (var files = Files.list(first.getParent())) {
                assertEquals(1L, files.count());
            }
        }
    }

    @Test
    public void osAndArchitectureSelectionDoesNotTreatOtherSystemsAsLinux() {
        assertEquals(SteamPlatform.WINDOWS_X64, SteamPlatform.resolve("Windows 11", "amd64"));
        assertEquals(SteamPlatform.WINDOWS_X64, SteamPlatform.resolve("Windows 10", "x86_64"));
        assertEquals(SteamPlatform.LINUX_X64, SteamPlatform.resolve("Linux", "amd64"));
        assertEquals(SteamPlatform.LINUX_X64, SteamPlatform.resolve("Linux", "x86_64"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> SteamPlatform.resolve("Darwin", "x86_64"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> SteamPlatform.resolve("Linux", "aarch64"));
    }

    @Test
    public void packedLinuxAndWindowsConnectionCallbacksDecodeTheSameValues() {
        for (SteamPlatform platform : SteamPlatform.values()) {
            try (Arena arena = Arena.ofConfined()) {
                var memory = arena.allocate(platform.callbackBytes(), 8);
                memory.set(JAVA_INT, 0, 42);
                memory.set(
                        JAVA_LONG_UNALIGNED,
                        platform.callbackInfo() + SteamOffsets.IDENTITY_DATA,
                        76561198000000001L);
                memory.set(JAVA_INT, platform.callbackInfo() + SteamOffsets.INFO_STATE, 3);
                memory.set(JAVA_INT, platform.callbackOldState(), 2);
                var event = SteamOffsets.readCallback(memory, platform);
                assertEquals(42, event.m_hConn);
                assertEquals(3, event.m_info.m_eState);
                assertEquals(2, event.m_eOldState);
                assertEquals(
                        76561198000000001L,
                        ByteBuffer.wrap(event.m_info.m_identityRemote.m_data)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .getLong());
            }
        }
    }
}
