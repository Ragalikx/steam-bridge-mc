package steambridge.voice;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import steambridge.core.*;

import su.plo.voice.api.client.event.socket.*;
import su.plo.voice.api.client.socket.UdpClient;
import su.plo.voice.api.server.event.socket.*;
import su.plo.voice.api.server.socket.UdpServer;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class PlasmoIntegrationTest {
    @After
    public void reset() {
        VoiceIntegration.install(null);
    }

    @Test
    public void apiClientRoundTripsAndDelegateClosureReleasesRelay() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        PlasmoClientAddon addon = new PlasmoClientAddon(client -> client instanceof FakeUdpClient);
        try (FakeUdpClient original = new FakeUdpClient()) {
            UdpClientConnectEvent event =
                    new UdpClientConnectEvent(
                            original, new ConnectionPacket(UUID.randomUUID(), "127.0.0.1", 24454));
            addon.connecting(event);
            UdpClient client = event.getClient();
            assertNotSame(original, client);
            client.connect("127.0.0.1", 24454);
            assertNotEquals(24454, original.port);
            assertEquals(original.getSecret(), client.getSecret());
            for (int length : new int[] {1, 9000, 2, 4096}) {
                byte[] data = new byte[length];
                Arrays.fill(data, (byte) length);
                original.socket.send(
                        new DatagramPacket(
                                data, length, InetAddress.getByName("127.0.0.1"), original.port));
                DatagramPacket reply = new DatagramPacket(new byte[65507], 65507);
                original.socket.receive(reply);
                assertArrayEquals(data, Arrays.copyOf(reply.getData(), reply.getLength()));
            }
            addon.closed(new UdpClientClosedEvent(original, UdpClientClosedEvent.Reason.RECONNECT));
            assertNull(transport.client.get());
            assertTrue(client.isClosed());
        }
    }

    @Test
    public void externalVoiceEndpointAndOrdinaryIpStayUntouched() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        try (FakeUdpClient original = new FakeUdpClient()) {
            PlasmoUdpClient client = new PlasmoUdpClient(original);
            client.connect("192.0.2.1", 24454);
            assertEquals("192.0.2.1", original.host);
            assertEquals(24454, original.port);
            assertNull(transport.client.get());
            client.close(UdpClientClosedEvent.Reason.DISCONNECT);
        }
        transport.active = false;
        try (FakeUdpClient original = new FakeUdpClient()) {
            UdpClientConnectEvent event =
                    new UdpClientConnectEvent(original, new ConnectionPacket());
            new PlasmoClientAddon().connecting(event);
            assertSame(original, event.getClient());
        }
    }

    @Test
    public void cancelledEventAndFailedConnectDoNotLeakRelay() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        try (FakeUdpClient original = new FakeUdpClient()) {
            UdpClientConnectEvent event =
                    new UdpClientConnectEvent(original, new ConnectionPacket());
            event.setCancelled(true);
            new PlasmoClientAddon().connecting(event);
            assertSame(original, event.getClient());
            original.fail = true;
            PlasmoUdpClient client = new PlasmoUdpClient(original);
            try {
                client.connect("127.0.0.1", 24454);
                fail("Expected failure");
            } catch (IllegalStateException expected) {
            }
            assertNull(transport.client.get());
            assertTrue(client.isClosed());
        }
    }

    @Test
    public void anotherAddonsSocketStaysUntouched() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        try (FakeUdpClient original = new FakeUdpClient()) {
            UdpClientConnectEvent event =
                    new UdpClientConnectEvent(original, new ConnectionPacket());
            new PlasmoClientAddon().connecting(event);
            assertSame(original, event.getClient());
            assertNull(transport.client.get());
        }
    }

    @Test
    public void serverRestartIgnoresOldStopAndTracksActualPort() {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        PlasmoServerAddon addon = new PlasmoServerAddon();
        UdpServer first = server(45678);
        UdpServer second = server(45679);
        addon.started(new UdpServerStartedEvent(first));
        assertEquals(45678, transport.port);
        addon.started(new UdpServerStartedEvent(second));
        addon.stopped(new UdpServerStoppedEvent(first));
        assertEquals(45679, transport.port);
        addon.stopped(new UdpServerStoppedEvent(second));
        assertEquals(0, transport.port);
    }

    private UdpServer server(int port) {
        return new UdpServer() {
            public void start(String host, int ignored) {}

            public void stop() {}

            public Optional<InetSocketAddress> getRemoteAddress() {
                return Optional.of(new InetSocketAddress("127.0.0.1", port));
            }
        };
    }

    private static final class FakeUdpClient implements UdpClient, AutoCloseable {
        final DatagramSocket socket = new DatagramSocket();
        final UUID secret = UUID.randomUUID();
        String host;
        int port;
        boolean closed;
        boolean fail;

        FakeUdpClient() throws Exception {
            socket.setSoTimeout(2000);
        }

        public void connect(String host, int port) {
            if (fail) throw new IllegalStateException("Connect failed");
            this.host = host;
            this.port = port;
        }

        public void close(UdpClientClosedEvent.Reason reason) {
            closed = true;
        }

        public void close() {
            socket.close();
        }

        public void sendPacket(Packet<?> packet) {}

        public UUID getSecret() {
            return secret;
        }

        public Optional<InetSocketAddress> getRemoteAddress() {
            return Optional.of(new InetSocketAddress(host, port));
        }

        public boolean isClosed() {
            return closed;
        }

        public boolean isConnected() {
            return !closed;
        }

        public boolean isTimedOut() {
            return false;
        }

        public long getKeepAlive() {
            return 123;
        }
    }

    private static final class FakeTransport implements VoiceTransport {
        boolean active = true;
        final AtomicReference<VoiceClient> client = new AtomicReference<>();
        int port;

        public boolean isClientActive() {
            return active;
        }

        public VoiceClient voiceClient() {
            return client.get();
        }

        public boolean claimVoiceClient(VoiceClient value, int port) {
            return client.compareAndSet(null, value);
        }

        public void releaseVoiceClient(VoiceClient value) {
            client.compareAndSet(value, null);
        }

        public void sendFromClientImpl(byte[] data) {
            try {
                client.get().receiveVoice(data, InetAddress.getByName("127.0.0.1"), 24454);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        public void registerBoundPort(int port) {
            this.port = port;
        }

        public void unregisterBoundPort(int port) {
            if (this.port == port) this.port = 0;
        }
    }
}
