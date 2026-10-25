package steambridge.steam;

import static org.junit.Assert.*;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.Test;

import steambridge.steam.NativeAllocation.Buffer;
import steambridge.steam.SteamOffsets.SteamNetConnectionInfo;
import steambridge.steam.SteamOffsets.SteamNetConnectionRealTimeStatus;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SteamSocketsApiTest {
    @Test
    public void duplicatePointersAreRejectedAndReleasedOnlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        try (NativeAllocation allocation = new NativeAllocation(16)) {
            Buffer message = allocation.buffer();
            message.clear();
            SteamSocketsApi api =
                    new SteamSocketsApi(
                            stub(
                                    (name, args) -> {
                                        if (name.endsWith("ReceiveMessagesOnConnection")) {
                                            Pointer pointers = (Pointer) args[2];
                                            pointers.setPointer(0, message);
                                            pointers.setPointer(Native.POINTER_SIZE, message);
                                            return 2;
                                        }
                                        if (name.endsWith("_Release")) releases.incrementAndGet();
                                        return null;
                                    }),
                            new Pointer(1),
                            new Pointer(2));
            try {
                assertThrows(IllegalStateException.class, () -> api.receiveMessages(1));
                assertEquals(1, releases.get());
            } finally {
                api.dispose();
            }
        }
    }

    @Test
    public void queueTelemetryFailureDoesNotReuseStaleMetrics() {
        AtomicInteger queries = new AtomicInteger();
        SteamSocketsApi api =
                new SteamSocketsApi(
                        stub(
                                (name, args) -> {
                                    if (name.endsWith("GetConnectionRealTimeStatus")) {
                                        SteamNetConnectionRealTimeStatus status =
                                                (SteamNetConnectionRealTimeStatus) args[2];
                                        status.m_cbPendingReliable = 123;
                                        status.m_usecQueueTime = 42_000;
                                        status.m_nSendRateBytesPerSecond = 345_000;
                                        status.write();
                                        return queries.getAndIncrement() == 0 ? 1 : 2;
                                    }
                                    return null;
                                }),
                        new Pointer(1),
                        new Pointer(2));
        try {
            steambridge.core.SendQueueState value = api.querySendQueue(1);
            assertTrue(value.known);
            assertEquals(123, value.pendingReliableBytes);
            assertEquals(42_000, value.queueMicros);
            assertEquals(345_000, value.sendRateBytes);
            assertFalse(api.querySendQueue(1).known);
        } finally {
            api.dispose();
        }
    }

    @Test
    public void jnaUsesSharedNativeQueuePolicy() {
        java.util.Map<Integer, Integer> configs = new java.util.HashMap<>();
        SteamSocketsApi api =
                new SteamSocketsApi(
                        stub(
                                (name, args) -> {
                                    if (name.endsWith("SetConfigValue")) {
                                        if (((Integer) args[4]) == 1)
                                            configs.put(
                                                    (Integer) args[1],
                                                    ((Pointer) args[5]).getInt(0));
                                        return (byte) 1;
                                    }
                                    return null;
                                }),
                        new Pointer(1),
                        new Pointer(2));
        try {
            api.configureForGameTraffic(true, "", 30, 60);
            assertEquals(
                    Integer.valueOf(steambridge.core.TransportPolicy.NATIVE_SEND_BUFFER_BYTES),
                    configs.get(9));
            assertEquals(
                    Integer.valueOf(steambridge.core.TransportPolicy.SEND_RATE_MAX_BYTES),
                    configs.get(11));
        } finally {
            api.dispose();
        }
    }

    @Test
    public void rejectedCallbackRegistrationIsReported() {
        SteamSocketsApi api =
                new SteamSocketsApi(stub((name, args) -> null), new Pointer(1), new Pointer(2));
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> api.installConnectionStatusCallback(event -> {}));
        } finally {
            api.dispose();
            SteamSocketsApi.nativeShutdownComplete();
        }
    }

    @Test
    public void callbackAfterFailedRemovalCannotDeliverStaleEvents() {
        java.util.concurrent.atomic.AtomicReference<SteamSocketsApi.ConnectionStatusChangedCallback>
                callback = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        SteamSocketsApi api =
                new SteamSocketsApi(
                        stub(
                                (name, args) -> {
                                    if (name.endsWith(
                                                    "SetGlobalCallback_SteamNetConnectionStatusChanged")
                                            && args[1] != null) {
                                        callback.set(
                                                (SteamSocketsApi.ConnectionStatusChangedCallback)
                                                        args[1]);
                                        return (byte) 1;
                                    }
                                    return null;
                                }),
                        new Pointer(1),
                        new Pointer(2));
        try {
            api.installConnectionStatusCallback(event -> deliveries.incrementAndGet());
            api.dispose();
            callback.get().invoke(new Pointer(1));
            assertEquals(0, deliveries.get());
        } finally {
            api.dispose();
            SteamSocketsApi.nativeShutdownComplete();
        }
    }

    @Test
    public void invalidCountStillReleasesAvailablePointers() {
        Buffer message = new Buffer(16);
        AtomicInteger releases = new AtomicInteger();
        SteamSocketsApi.FlatApi stub =
                stub(
                        (name, args) -> {
                            if (name.endsWith("ReceiveMessagesOnConnection")) {
                                ((Pointer) args[2]).setPointer(0, message);
                                return Integer.MAX_VALUE;
                            }
                            if (name.endsWith("_Release")) releases.incrementAndGet();
                            return null;
                        });
        SteamSocketsApi api = new SteamSocketsApi(stub, new Pointer(1), new Pointer(2));
        try {
            api.receiveMessages(1);
            fail("Invalid count accepted");
        } catch (IllegalStateException expected) {
            assertEquals(1, releases.get());
        } finally {
            api.dispose();
            message.release();
        }
    }

    @Test
    public void releaseFailureDoesNotSkipRemainingMessages() {
        List<Buffer> messages = new ArrayList<Buffer>();
        AtomicInteger releases = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            Buffer message = new Buffer(16);
            message.clear();
            messages.add(message);
        }
        SteamSocketsApi.FlatApi stub =
                stub(
                        (name, args) -> {
                            if (name.endsWith("ReceiveMessagesOnConnection")) {
                                for (int i = 0; i < 3; i++)
                                    ((Pointer) args[2])
                                            .setPointer(
                                                    (long) i * Native.POINTER_SIZE,
                                                    messages.get(i));
                                return 3;
                            }
                            if (name.endsWith("_Release") && releases.incrementAndGet() == 1)
                                throw new IllegalStateException("release failed");
                            return null;
                        });
        SteamSocketsApi api = new SteamSocketsApi(stub, new Pointer(1), new Pointer(2));
        try {
            api.receiveMessages(1);
            fail("Release failure hidden");
        } catch (IllegalStateException expected) {
            assertEquals(3, releases.get());
        } finally {
            api.dispose();
            for (Buffer memory : messages) memory.release();
        }
    }

    @Test
    public void invalidPayloadReleasesEntireBatch() {
        List<Buffer> messages = new ArrayList<Buffer>();
        List<Buffer> payloads = new ArrayList<Buffer>();
        AtomicInteger releases = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            Buffer data = new Buffer(4);
            Buffer message = new Buffer(16);
            message.setPointer(0, data);
            message.setInt(8, i == 31 ? -1 : 4);
            message.setInt(12, 1);
            messages.add(message);
            payloads.add(data);
        }
        SteamSocketsApi.FlatApi stub =
                stub(
                        (name, args) -> {
                            if (name.endsWith("ReceiveMessagesOnConnection")) {
                                Pointer target = (Pointer) args[2];
                                for (int i = 0; i < messages.size(); i++)
                                    target.setPointer(
                                            (long) i * Native.POINTER_SIZE, messages.get(i));
                                return messages.size();
                            }
                            if (name.endsWith("_Release")) releases.incrementAndGet();
                            return null;
                        });
        SteamSocketsApi api = new SteamSocketsApi(stub, new Pointer(1), new Pointer(2));
        try {
            api.receiveMessages(1);
            fail("Invalid payload accepted");
        } catch (IllegalStateException expected) {
            assertEquals(64, releases.get());
        } finally {
            api.dispose();
            for (Buffer memory : messages) memory.release();
            for (Buffer memory : payloads) memory.release();
        }
    }

    @Test
    public void failedSnapshotDoesNotReusePreviousPing() {
        AtomicInteger snapshots = new AtomicInteger();
        SteamSocketsApi.FlatApi stub =
                stub(
                        (name, args) -> {
                            if (name.endsWith("GetConnectionInfo")) {
                                SteamNetConnectionInfo info = (SteamNetConnectionInfo) args[2];
                                info.m_eState = SteamSocketsApi.STATE_CONNECTED;
                                info.write();
                                return (byte) 1;
                            }
                            if (name.endsWith("GetConnectionRealTimeStatus")) {
                                if (snapshots.getAndIncrement() == 0) {
                                    SteamNetConnectionRealTimeStatus status =
                                            (SteamNetConnectionRealTimeStatus) args[2];
                                    status.m_nPing = 42;
                                    status.write();
                                    return 1;
                                }
                                return 3;
                            }
                            if (name.endsWith("GetSteamID64")) return 7L;
                            return null;
                        });
        SteamSocketsApi api = new SteamSocketsApi(stub, new Pointer(1), new Pointer(2));
        try {
            assertEquals(42, api.snapshotConnection(1).getPingMs());
            assertEquals(-1, api.snapshotConnection(2).getPingMs());
        } finally {
            api.dispose();
        }
    }

    @Test
    public void sendsReadableSliceWithoutChangingReaderIndex() {
        List<byte[]> sent = new ArrayList<byte[]>();
        SteamSocketsApi.FlatApi stub =
                stub(
                        (name, args) -> {
                            if (name.endsWith("SendMessageToConnection")) {
                                sent.add(((Pointer) args[2]).getByteArray(0, (Integer) args[3]));
                                return 1;
                            }
                            return null;
                        });
        SteamSocketsApi api = new SteamSocketsApi(stub, new Pointer(1), new Pointer(2));
        ByteBuf direct = Unpooled.directBuffer(4).writeBytes(new byte[] {0, 1, 2, 3});
        ByteBuf composite = Unpooled.wrappedBuffer(new byte[] {4}, new byte[] {5, 6});
        try {
            direct.readByte();
            assertEquals(1, api.sendMessageFromByteBuf(1, direct, 3, 8));
            assertEquals(1, direct.readerIndex());
            assertEquals(1, api.sendMessageFromByteBuf(1, composite, 3, 8));
            assertArrayEquals(new byte[] {1, 2, 3}, sent.get(0));
            assertArrayEquals(new byte[] {4, 5, 6}, sent.get(1));
            api.dispose();
            assertEquals(3, api.sendMessageFromByteBuf(1, direct, 3, 8));
            assertEquals(2, sent.size());
        } finally {
            api.dispose();
            direct.release();
            composite.release();
        }
    }

    private interface Handler {
        Object invoke(String name, Object[] arguments);
    }

    private static SteamSocketsApi.FlatApi stub(Handler handler) {
        return (SteamSocketsApi.FlatApi)
                Proxy.newProxyInstance(
                        SteamSocketsApi.FlatApi.class.getClassLoader(),
                        new Class<?>[] {SteamSocketsApi.FlatApi.class},
                        (proxy, method, args) -> {
                            Object result = handler.invoke(method.getName(), args);
                            if (result != null || method.getReturnType() == void.class)
                                return result;
                            if (method.getReturnType() == byte.class) return (byte) 0;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                        });
    }
}
