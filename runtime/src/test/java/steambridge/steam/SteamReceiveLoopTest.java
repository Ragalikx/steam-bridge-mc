package steambridge.steam;

import static org.junit.Assert.*;

import org.junit.Test;

import steambridge.core.InboundMessage;

import java.lang.reflect.Field;
import java.util.Map;

public class SteamReceiveLoopTest {
    private static final InboundMessage[] EMPTY = new InboundMessage[0];

    private static final class Runtime extends SteamRuntime<SteamGameClient, SteamGameServer> {
        InboundMessage[] batch = EMPTY;
        int failing;

        protected void clearProfiles() {}

        protected void invalidateProfile(long id, boolean avatar) {}

        public void handleSteamShutdown() {}

        protected InboundMessage[] pollMessages(int connection, int maximum) {
            if (connection == failing) throw new IllegalStateException("synthetic receive failure");
            return batch;
        }

        @SuppressWarnings("unchecked")
        Map<Integer, LoopbackBridge> map(String name) throws Exception {
            Field field = SteamRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<Integer, LoopbackBridge>) field.get(this);
        }

        public SteamConnectionStatus getConnectionStatusByHandle(int handle) {
            return new SteamConnectionStatus(
                    76561198000000001L,
                    handle,
                    true,
                    true,
                    false,
                    true,
                    3,
                    0,
                    "secret-ticket",
                    4096,
                    0,
                    42,
                    0,
                    0,
                    0,
                    0,
                    12000,
                    "private-address");
        }
    }

    private static LoopbackBridge bridge(Runtime runtime, int connection) throws Exception {
        LoopbackBridge bridge = new LoopbackBridge(connection, runtime);
        runtime.map("loopbackByConnection").put(connection, bridge);
        return bridge;
    }

    @Test
    public void nullAndEmptyBatchesNeverReportWork() throws Exception {
        Runtime runtime = new Runtime();
        LoopbackBridge bridge = bridge(runtime, 1);
        try {
            assertFalse(runtime.drainConnection(1));
            runtime.batch = null;
            assertFalse(runtime.drainConnection(1));
        } finally {
            bridge.abortLocal();
        }
    }

    @Test
    public void nonemptyBatchIsDeliveredAndReleasedWhenClosed() throws Exception {
        Runtime runtime = new Runtime();
        LoopbackBridge bridge = bridge(runtime, 1);
        runtime.batch =
                new InboundMessage[] {
                    new InboundMessage() {
                        public int getConnection() {
                            return 1;
                        }

                        public byte[] getData() {
                            return new byte[] {1, 2, 3};
                        }
                    }
                };
        assertTrue(runtime.drainConnection(1));
        assertEquals(3, bridge.queuedInboundBytes());
        bridge.abortLocal();
        assertEquals(0, bridge.queuedInboundBytes());
    }

    @Test
    public void emptyNativeTailCompletesBothBackendContracts() throws Exception {
        Runtime runtime = new Runtime();
        LoopbackBridge bridge = bridge(runtime, 1);
        runtime.running.set(true);
        runtime.map("peerClosing").put(1, bridge);
        try {
            assertTrue(runtime.drainPeerMessages(bridge));
            runtime.batch = null;
            assertTrue(runtime.drainPeerMessages(bridge));
        } finally {
            runtime.running.set(false);
            bridge.abortLocal();
        }
    }

    @Test
    public void brokenReceiveDoesNotCloseOtherPeers() throws Exception {
        Runtime runtime = new Runtime();
        LoopbackBridge first = bridge(runtime, 1);
        LoopbackBridge second = bridge(runtime, 2);
        runtime.failing = 1;
        try {
            assertFalse(runtime.drainConnection(1));
            assertTrue(first.isClosed());
            assertFalse(second.isClosed());
            assertFalse(runtime.drainConnection(2));
        } finally {
            first.abortLocal();
            second.abortLocal();
        }
    }

    @Test
    public void diagnosticsIncludeQueuesButOmitIdentityAndSecrets() throws Exception {
        Runtime runtime = new Runtime();
        LoopbackBridge bridge = bridge(runtime, 1);
        try {
            String report = runtime.diagnosticReport();
            assertTrue(report.contains("route=Valve Relay"));
            assertTrue(report.contains("rttMs=42"));
            assertTrue(report.contains("nativeQueuedBytes=4096"));
            assertTrue(report.contains("nativeQueueMicros=12000"));
            assertFalse(report.contains("76561198000000001"));
            assertFalse(report.contains("secret-ticket"));
            assertFalse(report.contains("private-address"));
        } finally {
            bridge.abortLocal();
        }
    }
}
