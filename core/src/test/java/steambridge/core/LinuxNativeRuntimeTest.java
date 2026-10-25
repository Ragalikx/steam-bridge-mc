package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LinuxNativeRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private LinuxNativeRuntime.Resources resources() {
        return name -> LinuxNativeRuntimeTest.class.getResourceAsStream("/" + name);
    }

    @Test
    public void missingRuntimeAndUnknownLibraryFailBeforeCreatingFiles() {
        LinuxNativeRuntime runtime = new LinuxNativeRuntime();
        assertThrows(
                IllegalStateException.class,
                () -> runtime.resolve("steam_api", temporary.getRoot().toPath()));
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.resolve("other", temporary.getRoot().toPath()));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test
    public void bothPinnedLibrariesAreExtractedToTheSameDirectory() throws Exception {
        LinuxNativeRuntime runtime = new LinuxNativeRuntime();
        runtime.install(1, resources());
        Path api = runtime.resolve("steam_api", temporary.getRoot().toPath());
        Path bridge = runtime.resolve("steamworks4j", temporary.getRoot().toPath());
        assertEquals(api.getParent(), bridge.getParent());
        assertEquals(388288L, Files.size(api));
        assertEquals(310528L, Files.size(bridge));
        assertEquals(api, runtime.resolve("steam_api", temporary.getRoot().toPath()));
    }

    @Test
    public void badBridgeIsRejectedBeforeWritingTheApiLibrary() {
        LinuxNativeRuntime runtime = new LinuxNativeRuntime();
        runtime.install(
                1,
                name ->
                        name.equals("libsteam_api.so")
                                ? resources().open(name)
                                : new ByteArrayInputStream(new byte[1]));
        assertThrows(
                SecurityException.class,
                () -> runtime.resolve("steam_api", temporary.getRoot().toPath()));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test
    public void corruptCacheIsNotOverwritten() throws Exception {
        LinuxNativeRuntime runtime = new LinuxNativeRuntime();
        runtime.install(1, resources());
        Path path = runtime.resolve("steamworks4j", temporary.getRoot().toPath());
        Files.write(path, new byte[] {0});
        assertThrows(
                SecurityException.class,
                () -> runtime.resolve("steam_api", temporary.getRoot().toPath()));
        assertArrayEquals(new byte[] {0}, Files.readAllBytes(path));
    }

    @Test
    public void incompatibleOrDuplicateProviderCannotReplaceTheFirst() {
        LinuxNativeRuntime runtime = new LinuxNativeRuntime();
        assertThrows(IllegalArgumentException.class, () -> runtime.install(2, resources()));
        assertThrows(NullPointerException.class, () -> runtime.install(1, null));
        runtime.install(1, resources());
        assertThrows(IllegalStateException.class, () -> runtime.install(1, resources()));
    }

    @Test
    public void concurrentProvidersLeaveOnlyTheTwoVerifiedLibraries() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Path>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(
                        pool.submit(
                                () -> {
                                    LinuxNativeRuntime runtime = new LinuxNativeRuntime();
                                    runtime.install(1, resources());
                                    return runtime.resolve(
                                            "steam_api", temporary.getRoot().toPath());
                                }));
            }
            Path first = tasks.get(0).get(15, TimeUnit.SECONDS);
            for (Future<Path> task : tasks) assertEquals(first, task.get(15, TimeUnit.SECONDS));
            try (java.util.stream.Stream<Path> files = Files.list(first.getParent())) {
                assertEquals(2, files.count());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
