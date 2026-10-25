package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class SteamDatagramSocketImplTest {
    private static class Endpoint implements SteamDatagramSocketImpl.Endpoint {
        boolean active;
        byte[] sent;

        public boolean intercept(InetAddress address, int port) {
            return active;
        }

        public boolean activated(SteamDatagramSocketImpl socket, int port) {
            return true;
        }

        public void closed(SteamDatagramSocketImpl socket) {}

        public void send(byte[] data) {
            sent = data;
        }

        public void bound(int port) {}
    }

    private static class Socket extends DatagramSocket {
        Socket(SteamDatagramSocketImpl impl) throws SocketException {
            super(impl);
            bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        }
    }

    @Test
    public void realUdpHonoursPacketCapacityAndOffset() throws Exception {
        try (Socket receiver = new Socket(new SteamDatagramSocketImpl(new Endpoint()));
                DatagramSocket sender = new DatagramSocket()) {
            receiver.setSoTimeout(1000);
            byte[] payload = new byte[8000];
            Arrays.fill(payload, (byte) 42);
            sender.send(
                    new DatagramPacket(
                            payload,
                            payload.length,
                            InetAddress.getLoopbackAddress(),
                            receiver.getLocalPort()));
            byte[] target = new byte[32];
            Arrays.fill(target, (byte) 7);
            DatagramPacket packet = new DatagramPacket(target, 4, 10);
            receiver.receive(packet);
            assertEquals(10, packet.getLength());
            assertEquals(42, target[4]);
            assertEquals(7, target[14]);
            assertEquals(7, target[3]);
        }
    }

    @Test
    public void timeoutAndCloseUnblockReceive() throws Exception {
        Socket socket = new Socket(new SteamDatagramSocketImpl(new Endpoint()));
        socket.setSoTimeout(40);
        try {
            socket.receive(new DatagramPacket(new byte[8], 8));
            fail("Expected timeout");
        } catch (SocketTimeoutException expected) {
        }
        socket.setSoTimeout(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<?> receive =
                    pool.submit(
                            () -> {
                                started.countDown();
                                try {
                                    socket.receive(new DatagramPacket(new byte[8], 8));
                                    fail("Expected close");
                                } catch (SocketException expected) {
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            socket.close();
            receive.get(1, TimeUnit.SECONDS);
        } finally {
            socket.close();
            pool.shutdownNow();
        }
    }

    @Test
    public void steamSendReceiveAndPeekKeepPacketBoundaries() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.active = true;
        SteamDatagramSocketImpl impl = new SteamDatagramSocketImpl(endpoint);
        try (Socket socket = new Socket(impl)) {
            socket.connect(InetAddress.getLoopbackAddress(), 24454);
            socket.send(new DatagramPacket(new byte[] {9, 1, 2, 8}, 1, 2));
            assertArrayEquals(new byte[] {1, 2}, endpoint.sent);
            assertTrue(
                    impl.enqueueFromSteam(
                            new byte[] {3, 4, 5}, InetAddress.getLoopbackAddress(), 24454));
            DatagramPacket peek = new DatagramPacket(new byte[1], 1);
            assertEquals(24454, impl.peekData(peek));
            assertNotNull(peek.getAddress());
            DatagramPacket incoming = new DatagramPacket(new byte[8], 8);
            socket.receive(incoming);
            assertEquals(3, incoming.getLength());
            assertArrayEquals(new byte[] {3, 4, 5}, Arrays.copyOf(incoming.getData(), 3));
        }
        try {
            impl.send(new DatagramPacket(new byte[1], 1, InetAddress.getLoopbackAddress(), 24454));
            fail("Socket reopened");
        } catch (SocketException expected) {
        }
    }

    @Test
    public void fullVoiceQueueIsBounded() throws Exception {
        SteamDatagramSocketImpl impl = new SteamDatagramSocketImpl(new Endpoint());
        for (int i = 0; i < 512; i++) assertTrue(impl.enqueueFromSteam(new byte[1], null, 24454));
        assertFalse(impl.enqueueFromSteam(new byte[1], null, 24454));
        assertFalse(impl.enqueueFromSteam(new byte[65508], null, 24454));
        impl.close();
        assertFalse(impl.enqueueFromSteam(new byte[1], null, 24454));
    }

    @Test
    public void multicastOptionsAreAppliedAndMembershipCanBeReleased() throws Exception {
        SteamDatagramSocketImpl impl = new SteamDatagramSocketImpl(new Endpoint());
        try {
            impl.setTimeToLive(7);
            impl.setOption(SocketOptions.IP_MULTICAST_LOOP, true);
            assertEquals(7, impl.getTimeToLive());
            assertEquals(true, impl.getOption(SocketOptions.IP_MULTICAST_LOOP));
            java.util.Enumeration<NetworkInterface> networks =
                    NetworkInterface.getNetworkInterfaces();
            NetworkInterface network = null;
            while (networks.hasMoreElements()) {
                NetworkInterface candidate = networks.nextElement();
                boolean ipv4 = false;
                java.util.Enumeration<InetAddress> addresses = candidate.getInetAddresses();
                while (addresses.hasMoreElements())
                    if (addresses.nextElement() instanceof java.net.Inet4Address) ipv4 = true;
                if (ipv4
                        && candidate.isUp()
                        && candidate.supportsMulticast()
                        && !candidate.isLoopback()) {
                    network = candidate;
                    break;
                }
            }
            org.junit.Assume.assumeNotNull(network);
            impl.setOption(SocketOptions.IP_MULTICAST_IF2, network);
            assertEquals(network, impl.getOption(SocketOptions.IP_MULTICAST_IF2));
            InetSocketAddress group = new InetSocketAddress("224.0.2.60", 4445);
            impl.joinGroup(group, network);
            impl.leaveGroup(group, network);
        } finally {
            impl.close();
        }
    }
}
