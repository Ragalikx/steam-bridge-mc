package steambridge.panama;

import steambridge.core.NativeCallGate;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SteamLibrary implements AutoCloseable {
    private final Arena arena = Arena.ofShared();
    private final NativeCallGate gate = new NativeCallGate();
    private final SymbolLookup symbols;
    private final ConcurrentHashMap<String, MethodHandle> handles = new ConcurrentHashMap<>();

    public SteamLibrary(Path library) {
        try {
            SteamPlatform.current();
        } catch (RuntimeException unsupported) {
            arena.close();
            throw unsupported;
        }
        try {
            symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
        } catch (Throwable failure) {
            arena.close();
            throw failure;
        }
    }

    public static Path extract() throws Exception {
        return NativeRuntime.extract();
    }

    public Object call(String symbol, FunctionDescriptor descriptor, Object... arguments) {
        try (var lease = gate.enter()) {
            if (lease == null) throw new IllegalStateException("Steam native library is closed");
            MethodHandle handle =
                    handles.computeIfAbsent(
                            symbol + descriptor,
                            key ->
                                    Linker.nativeLinker()
                                            .downcallHandle(
                                                    symbols.find(symbol)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new UnsatisfiedLinkError(
                                                                                    "Missing Steam"
                                                                                        + " export:"
                                                                                        + " "
                                                                                            + symbol)),
                                                    descriptor));
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Steam call failed: " + symbol, failure);
        }
    }

    public boolean hasSymbol(String symbol) {
        try (var lease = gate.enter()) {
            return lease != null && symbols.find(symbol).isPresent();
        }
    }

    @Override
    public void close() {
        gate.stopAndRun(arena::close);
    }
}
