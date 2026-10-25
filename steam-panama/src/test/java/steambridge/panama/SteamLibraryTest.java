package steambridge.panama;

import static org.junit.Assert.*;

import static java.lang.foreign.ValueLayout.*;

import org.junit.Test;

import steambridge.steam.SteamOffsets;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class SteamLibraryTest {
    private static final AtomicReference<SteamOffsets.SteamNetConnectionStatusChangedCallback>
            received = new AtomicReference<>();

    private SteamLibrary library() {
        return new SteamLibrary(Path.of(System.getProperty("steambridge.test.library")));
    }

    @Test
    public void packagedSteamExportsSupportFfmWithoutSteamInitialization() {
        try (SteamLibrary library = library()) {
            for (String export : NativeBindings.all().keySet())
                assertTrue(export, library.hasSymbol(export));
        }
    }

    @Test
    public void identityRoundTripsAcrossActualSteamCAbi() {
        try (SteamLibrary library = library();
                Arena arena = Arena.ofConfined()) {
            MemorySegment identity = arena.allocate(SteamOffsets.IDENTITY_BYTES, 8);
            library.call(
                    "SteamAPI_SteamNetworkingIdentity_Clear",
                    FunctionDescriptor.ofVoid(ADDRESS),
                    identity);
            assertEquals(0, identity.get(JAVA_INT, 0));
            assertEquals(
                    (byte) 1,
                    (byte)
                            library.call(
                                    "SteamAPI_SteamNetworkingIdentity_IsInvalid",
                                    FunctionDescriptor.of(JAVA_BYTE, ADDRESS),
                                    identity));
            long expected = 76561198000000001L;
            library.call(
                    "SteamAPI_SteamNetworkingIdentity_SetSteamID64",
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG),
                    identity,
                    expected);
            assertEquals(16, identity.get(JAVA_INT, 0));
            assertEquals(8, identity.get(JAVA_INT, 4));
            assertEquals(
                    (byte) 0,
                    (byte)
                            library.call(
                                    "SteamAPI_SteamNetworkingIdentity_IsInvalid",
                                    FunctionDescriptor.of(JAVA_BYTE, ADDRESS),
                                    identity));
            assertEquals(
                    expected,
                    (long)
                            library.call(
                                    "SteamAPI_SteamNetworkingIdentity_GetSteamID64",
                                    FunctionDescriptor.of(JAVA_LONG, ADDRESS),
                                    identity));
        }
    }

    public static void onStatus(MemorySegment pointer) {
        received.set(SteamOffsets.readCallback(pointer));
    }

    @Test
    public void callbackUpcallCopiesDataBeforeForeignArenaCloses() throws Throwable {
        MemorySegment stub;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment event = arena.allocate(SteamOffsets.CALLBACK_BYTES, 8);
            event.set(JAVA_INT, 0, 42);
            event.set(
                    JAVA_LONG_UNALIGNED,
                    SteamOffsets.CALLBACK_INFO + SteamOffsets.IDENTITY_DATA,
                    76561198000000001L);
            event.set(JAVA_INT, SteamOffsets.CALLBACK_INFO + SteamOffsets.INFO_STATE, 3);
            event.set(JAVA_INT, SteamOffsets.CALLBACK_OLD_STATE, 2);
            MethodHandle target =
                    MethodHandles.lookup()
                            .findStatic(
                                    SteamLibraryTest.class,
                                    "onStatus",
                                    MethodType.methodType(void.class, MemorySegment.class));
            stub =
                    Linker.nativeLinker()
                            .upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS), arena);
            MethodHandle downcall =
                    Linker.nativeLinker().downcallHandle(stub, FunctionDescriptor.ofVoid(ADDRESS));
            downcall.invokeExact(event);
        }
        assertFalse(stub.scope().isAlive());
        var event = received.getAndSet(null);
        assertEquals(42, event.m_hConn);
        assertEquals(3, event.m_info.m_eState);
        assertEquals(2, event.m_eOldState);
        assertEquals(
                76561198000000001L,
                java.nio.ByteBuffer.wrap(event.m_info.m_identityRemote.m_data)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .getLong());
    }

    @Test
    public void closedLibraryRejectsCalls() {
        SteamLibrary library = library();
        library.close();
        assertFalse(library.hasSymbol("SteamAPI_InitFlat"));
        assertThrows(
                IllegalStateException.class,
                () -> library.call("SteamAPI_GetHSteamPipe", FunctionDescriptor.of(JAVA_INT)));
    }

    @Test
    public void incorrectBindingIsRejectedBeforeAnyNativeCall() {
        assertThrows(IllegalArgumentException.class, () -> SteamAPI.call("SteamAPI_Unknown", null));
        assertThrows(
                IllegalArgumentException.class, () -> SteamAPI.call("SteamAPI_Shutdown", JAVA_INT));
        assertThrows(
                IllegalArgumentException.class, () -> SteamAPI.call("SteamAPI_InitFlat", JAVA_INT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SteamAPI.call(
                                "SteamAPI_ManualDispatch_GetNextCallback",
                                JAVA_INT,
                                0,
                                MemorySegment.NULL));
    }

    @Test
    public void shutdownNotificationDoesNotRequirePayloadBytes() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SteamAPI.setListeners((id, avatar) -> {}, calls::incrementAndGet);
        try {
            SteamAPI.dispatchCallback(704, MemorySegment.NULL, 0);
            assertEquals(1, calls.get());
        } finally {
            SteamAPI.setListeners((id, avatar) -> {}, () -> {});
        }
    }
}
