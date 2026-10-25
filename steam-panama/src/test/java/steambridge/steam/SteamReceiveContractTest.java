package steambridge.steam;

import static org.junit.Assert.*;

import static java.lang.foreign.ValueLayout.*;

import org.junit.*;

import steambridge.core.TransportPolicy;
import steambridge.panama.SteamAPI;
import steambridge.panama.SteamLibrary;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public class SteamReceiveContractTest {
    private static int count, calls, pending, rate, statusResult;
    private static long queue;
    private static final Map<Integer, Integer> configs = new HashMap<>();
    private static final List<byte[]> sent = new ArrayList<>();
    private Arena arena;
    private SteamLibrary library;
    private Object previous;
    private SteamSocketsApi sockets;

    public static MemorySegment api() {
        return MemorySegment.ofAddress(1);
    }

    public static int receive(MemorySegment self, int handle, MemorySegment pointers, int maximum) {
        calls++;
        return count;
    }

    public static int send(
            MemorySegment self,
            int handle,
            MemorySegment payload,
            int length,
            int flags,
            MemorySegment number) {
        sent.add(payload.reinterpret(length).toArray(JAVA_BYTE));
        return 1;
    }

    public static int status(
            MemorySegment self, int handle, MemorySegment target, int lanes, MemorySegment lane) {
        if (statusResult == 1) {
            MemorySegment value = target.reinterpret(120);
            value.set(JAVA_INT, 40, pending);
            value.set(JAVA_LONG, 48, queue);
            value.set(JAVA_INT, 32, rate);
        }
        return statusResult;
    }

    public static byte config(
            MemorySegment self, int key, int scope, long owner, int type, MemorySegment value) {
        configs.put(key, value.reinterpret(4).get(JAVA_INT, 0));
        return 1;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private MemorySegment stub(String method, MethodType type, FunctionDescriptor descriptor)
            throws Exception {
        return Linker.nativeLinker()
                .upcallStub(
                        MethodHandles.lookup().findStatic(getClass(), method, type),
                        descriptor,
                        arena);
    }

    @Before
    public void setup() throws Exception {
        count = calls = pending = rate = statusResult = 0;
        queue = 0;
        configs.clear();
        sent.clear();
        arena = Arena.ofShared();
        library = new SteamLibrary(Path.of(System.getProperty("steambridge.test.library")));
        MemorySegment api =
                stub(
                        "api",
                        MethodType.methodType(MemorySegment.class),
                        FunctionDescriptor.of(ADDRESS));
        Map<String, MemorySegment> symbols = new HashMap<>();
        symbols.put("SteamAPI_SteamNetworkingSockets_SteamAPI_v012", api);
        symbols.put("SteamAPI_SteamNetworkingUtils_SteamAPI_v004", api);
        symbols.put(
                "SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection",
                stub(
                        "receive",
                        MethodType.methodType(
                                int.class,
                                MemorySegment.class,
                                int.class,
                                MemorySegment.class,
                                int.class),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)));
        symbols.put(
                "SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus",
                stub(
                        "status",
                        MethodType.methodType(
                                int.class,
                                MemorySegment.class,
                                int.class,
                                MemorySegment.class,
                                int.class,
                                MemorySegment.class),
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)));
        symbols.put(
                "SteamAPI_ISteamNetworkingSockets_SendMessageToConnection",
                stub(
                        "send",
                        MethodType.methodType(
                                int.class,
                                MemorySegment.class,
                                int.class,
                                MemorySegment.class,
                                int.class,
                                int.class,
                                MemorySegment.class),
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
                                ADDRESS)));
        symbols.put(
                "SteamAPI_ISteamNetworkingUtils_SetConfigValue",
                stub(
                        "config",
                        MethodType.methodType(
                                byte.class,
                                MemorySegment.class,
                                int.class,
                                int.class,
                                long.class,
                                int.class,
                                MemorySegment.class),
                        FunctionDescriptor.of(
                                JAVA_BYTE, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT,
                                ADDRESS)));
        field(SteamLibrary.class, "symbols")
                .set(library, (SymbolLookup) name -> Optional.ofNullable(symbols.get(name)));
        Field binding = field(SteamAPI.class, "library");
        previous = binding.get(null);
        binding.set(null, library);
        sockets = SteamSocketsApi.load();
    }

    @After
    public void cleanup() throws Exception {
        if (sockets != null)
            ((Arena) field(SteamSocketsApi.class, "callbacks").get(sockets)).close();
        field(SteamAPI.class, "library").set(null, previous);
        if (library != null) library.close();
        if (arena != null) arena.close();
    }

    private static final class Runtime extends SteamRuntime {
        protected void clearProfiles() {}

        protected void invalidateProfile(long id, boolean avatar) {}

        public void handleSteamShutdown() {}
    }

    @SuppressWarnings("unchecked")
    private Runtime runtime(LoopbackBridge[] output) throws Exception {
        Runtime runtime = new Runtime();
        field(SteamRuntime.class, "socketsApi").set(runtime, sockets);
        LoopbackBridge bridge = new LoopbackBridge(1, runtime);
        ((Map<Integer, LoopbackBridge>)
                        field(SteamRuntime.class, "loopbackByConnection").get(runtime))
                .put(1, bridge);
        output[0] = bridge;
        return runtime;
    }

    @Test
    public void emptyNativeReceiveDoesNotCountAsWork() throws Exception {
        Runtime runtime = runtime(new LoopbackBridge[1]);
        Method drain = SteamRuntime.class.getDeclaredMethod("drainConnection", int.class);
        drain.setAccessible(true);
        for (int i = 0; i < 20; i++) assertEquals(false, drain.invoke(runtime, 1));
        assertEquals(20, calls);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void emptyTailCompletesPeerDrainWithoutWaitingForTimeout() throws Exception {
        LoopbackBridge[] bridge = new LoopbackBridge[1];
        Runtime runtime = runtime(bridge);
        runtime.running.set(true);
        ((Map<Integer, LoopbackBridge>) field(SteamRuntime.class, "peerClosing").get(runtime))
                .put(1, bridge[0]);
        assertTrue(runtime.drainPeerMessages(bridge[0]));
        assertEquals(1, calls);
    }

    @Test
    public void emptyBatchIsReusedAndInvalidCapacityDoesNotPoll() {
        assertSame(sockets.receiveMessages(1, 64), sockets.receiveMessages(1, 64));
        assertEquals(2, calls);
        assertEquals(0, sockets.receiveMessages(1, 0).length);
        assertEquals(2, calls);
        count = -1;
        assertThrows(IllegalStateException.class, () -> sockets.receiveMessages(1, 64));
    }

    @Test
    public void queueTelemetryAndFailureHaveTheSameMeaningAsJna() {
        pending = 123;
        queue = 42_000;
        rate = 345_000;
        statusResult = 1;
        var value = sockets.querySendQueue(1);
        assertTrue(value.known);
        assertEquals(123, value.pendingReliableBytes);
        assertEquals(42_000, value.queueMicros);
        assertEquals(345_000, value.sendRateBytes);
        statusResult = 2;
        assertFalse(sockets.querySendQueue(1).known);
    }

    @Test
    public void panamaUsesTheSharedNativeQueuePolicy() {
        sockets.configureForGameTraffic(true, "", 30, 60);
        assertEquals(Integer.valueOf(TransportPolicy.NATIVE_SEND_BUFFER_BYTES), configs.get(9));
        assertEquals(Integer.valueOf(TransportPolicy.SEND_RATE_MAX_BYTES), configs.get(11));
    }

    @Test
    public void directHeapAndCompositeSendPreservePayloadAndReaderIndex() {
        var direct = io.netty.buffer.Unpooled.directBuffer(4).writeBytes(new byte[] {0, 1, 2, 3});
        var heap = io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {4, 5, 6});
        var composite = io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {7}, new byte[] {8, 9});
        try {
            direct.readByte();
            assertEquals(1, sockets.sendMessageFromByteBuf(1, direct, 3, 8));
            assertEquals(1, direct.readerIndex());
            assertEquals(1, sockets.sendMessageFromByteBuf(1, heap, 3, 8));
            assertEquals(1, sockets.sendMessageFromByteBuf(1, composite, 3, 8));
            assertArrayEquals(new byte[] {1, 2, 3}, sent.get(0));
            assertArrayEquals(new byte[] {4, 5, 6}, sent.get(1));
            assertArrayEquals(new byte[] {7, 8, 9}, sent.get(2));
        } finally {
            direct.release();
            heap.release();
            composite.release();
        }
    }
}
