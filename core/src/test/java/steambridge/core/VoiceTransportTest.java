package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class VoiceTransportTest {
    private static final class Transport implements VoiceTransport {
        final AtomicReference<VoiceClient> client = new AtomicReference<VoiceClient>();

        public boolean isClientActive() {
            return true;
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

        public void registerBoundPort(int port) {}

        public void sendFromClientImpl(byte[] data) {
            client.get().receiveVoice(data, InetAddress.getLoopbackAddress(), 24454);
        }
    }

    @Test
    public void explicitSocketTunnelsWithoutGlobalFactory() throws Exception {
        Transport transport = new Transport();
        try (VoiceDatagramSocket socket = new VoiceDatagramSocket(transport, false)) {
            socket.bind(new InetSocketAddress(0));
            socket.setSoTimeout(1000);
            for (int length : new int[] {2, 4096, 1, 8192}) {
                byte[] bytes = new byte[length];
                Arrays.fill(bytes, (byte) length);
                socket.send(
                        new DatagramPacket(
                                bytes, bytes.length, InetAddress.getByName("127.0.0.1"), 24454));
                DatagramPacket response = new DatagramPacket(new byte[16384], 16384);
                socket.receive(response);
                assertArrayEquals(bytes, Arrays.copyOf(response.getData(), response.getLength()));
            }
            assertNotNull(transport.voiceClient());
        }
        assertNull(transport.voiceClient());
    }

    @Test
    public void localRelayReturnsVariableSizePacketsAndClosesPort() throws Exception {
        Transport transport = new Transport();
        LocalVoiceRelay relay = LocalVoiceRelay.open(transport, 24454);
        int port = relay.port();
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(1000);
            for (int length : new int[] {2, 4096, 1, 8192}) {
                byte[] bytes = new byte[length];
                Arrays.fill(bytes, (byte) length);
                client.send(
                        new DatagramPacket(
                                bytes, length, InetAddress.getByName("127.0.0.1"), port));
                DatagramPacket response = new DatagramPacket(new byte[16384], 16384);
                client.receive(response);
                assertArrayEquals(bytes, Arrays.copyOf(response.getData(), response.getLength()));
            }
            assertNull(LocalVoiceRelay.open(transport, 24455));
            assertSame(relay, transport.voiceClient());
        } finally {
            relay.closeVoice();
        }
        assertNull(transport.voiceClient());
        try (DatagramSocket replacement =
                new DatagramSocket(new InetSocketAddress("127.0.0.1", port))) {
            assertEquals(port, replacement.getLocalPort());
        }
    }
}
