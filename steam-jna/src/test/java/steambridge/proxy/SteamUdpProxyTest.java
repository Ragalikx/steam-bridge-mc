package steambridge.proxy;

import static org.junit.Assert.*;

import com.codedisaster.steamworks.SteamID;

import org.junit.Test;

import steambridge.core.*;
import steambridge.steam.*;
import steambridge.voice.VoiceNativeDriver;

import java.lang.reflect.Constructor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SteamUdpProxyTest {
    @Test
    public void localFailureClosesOnlyTheCurrentVoiceSocket() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        GameAttemptOwner first = () -> true;
        driver.owner = first;
        proxy.startClient(null, first);
        int old = driver.handle;
        proxy.stopClient(first);
        GameAttemptOwner second = () -> true;
        driver.owner = second;
        proxy.startClient(null, second);
        FakeClient socket = new FakeClient();
        assertTrue(proxy.claimVoiceClient(socket, 24454));
        proxy.onLocalConnectionClosed(old);
        assertTrue(proxy.isClientActive());
        assertEquals(0, socket.closed.get());
        proxy.onLocalConnectionClosed(driver.handle);
        assertFalse(proxy.isClientActive());
        assertEquals(1, socket.closed.get());
        proxy.onLocalConnectionClosed(driver.handle);
        assertEquals(1, socket.closed.get());
    }

    private SteamUdpProxy proxy(FakeDriver driver) throws Exception {
        Constructor<SteamUdpProxy> constructor = SteamUdpProxy.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SteamUdpProxy proxy = constructor.newInstance();
        proxy.install(driver);
        return proxy;
    }

    private SteamConnectionStatus status(int handle, int state) {
        return new SteamConnectionStatus(
                1, handle, true, false, false, false, state, 1000, "", 0, 0, -1, 0, 0, 0, 0, 0, "");
    }

    @Test
    public void oldVoiceServerCannotUnregisterReplacementOnSamePort() throws Exception {
        SteamUdpProxy proxy = proxy(new FakeDriver());
        Object first = new Object(), second = new Object();
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25565);
        proxy.registerBoundAddress(first, endpoint);
        proxy.registerBoundAddress(second, endpoint);
        proxy.unregisterBoundAddress(first, endpoint.getPort());
        assertEquals(endpoint, proxy.resolveVoiceTarget());
        proxy.unregisterBoundAddress(second, endpoint.getPort());
        assertEquals(24454, proxy.resolveVoiceTargetPort());
    }

    @Test
    public void legacySocketCannotOverwriteOrClearApiVoiceBinding() throws Exception {
        SteamUdpProxy proxy = proxy(new FakeDriver());
        Object owner = new Object();
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25565);
        proxy.registerBoundAddress(owner, endpoint);
        proxy.registerBoundPort(12345);
        proxy.unregisterBoundPort(endpoint.getPort());
        assertEquals(endpoint, proxy.resolveVoiceTarget());
        proxy.unregisterBoundAddress(owner, 12345);
        assertEquals(endpoint, proxy.resolveVoiceTarget());
        proxy.unregisterBoundAddress(owner, endpoint.getPort());
        proxy.registerBoundPort(12345);
        assertEquals(12345, proxy.resolveVoiceTargetPort());
        proxy.unregisterBoundPort(12345);
        assertEquals(24454, proxy.resolveVoiceTargetPort());
    }

    @Test
    public void steamHostStopPreservesLiveVoiceServiceUntilItsOwnerCloses() throws Exception {
        SteamUdpProxy proxy = proxy(new FakeDriver());
        Object owner = new Object();
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 25565);
        proxy.registerBoundAddress(owner, endpoint);
        proxy.stopServer();
        assertEquals(endpoint, proxy.resolveVoiceTarget());
        proxy.unregisterBoundAddress(owner, endpoint.getPort());
        assertEquals(24454, proxy.resolveVoiceTargetPort());
        proxy.registerBoundPort(12345);
        proxy.stopServer();
        assertEquals(24454, proxy.resolveVoiceTargetPort());
    }

    @Test
    public void oldCallbackAndOwnerCannotCloseReplacementVoiceSession() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        GameAttemptOwner first = () -> true;
        driver.owner = first;
        proxy.startClient(null, first);
        int old = driver.handle;
        proxy.stopClient(first);
        GameAttemptOwner second = () -> true;
        driver.owner = second;
        proxy.startClient(null, second);
        FakeClient socket = new FakeClient();
        assertTrue(proxy.claimVoiceClient(socket, 24454));
        proxy.onClientConnectionStatusChanged(
                old, 1, status(old, SteamSocketsApi.STATE_CLOSED_BY_PEER), 3);
        proxy.stopClient(first);
        assertTrue(proxy.ownsClientConn(driver.handle));
        assertSame(socket, proxy.voiceClient());
        assertEquals(0, socket.closed.get());
        proxy.stopClient(second);
        assertEquals(1, socket.closed.get());
        proxy.onClientConnectionStatusChanged(
                driver.handle, 1, status(driver.handle, SteamSocketsApi.STATE_CONNECTED), 2);
        assertFalse(proxy.isClientActive());
    }

    @Test
    public void oldSocketCannotSendThroughReplacementGameSession() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        driver.owner = () -> true;
        proxy.startClient(null, driver.owner);
        FakeClient first = new FakeClient();
        assertTrue(proxy.claimVoiceClient(first, 24454));
        proxy.stopClient();
        proxy.startClient(null, driver.owner);
        FakeClient second = new FakeClient();
        assertTrue(proxy.claimVoiceClient(second, 24454));
        proxy.sendFromClient(first, new byte[] {1});
        proxy.sendFromClient(second, new byte[] {2});
        assertArrayEquals(new byte[] {2}, driver.voicePackets.poll());
        assertNull(driver.voicePackets.poll());
        proxy.stopClient();
    }

    @Test
    public void claimingSocketDuringStopCannotLeaveItRegistered() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        driver.owner = () -> true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                proxy.startClient(null, driver.owner);
                FakeClient socket = new FakeClient();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> claimed =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return proxy.claimVoiceClient(socket, 24454);
                                });
                Future<?> stopped =
                        workers.submit(
                                () -> {
                                    start.await();
                                    proxy.stopClient();
                                    return null;
                                });
                start.countDown();
                boolean accepted = claimed.get(2, TimeUnit.SECONDS);
                stopped.get(2, TimeUnit.SECONDS);
                assertNull(proxy.voiceClient());
                assertFalse(proxy.isClientActive());
                assertEquals(accepted ? 1 : 0, socket.closed.get());
            }
        } finally {
            workers.shutdownNow();
            proxy.stopClient();
        }
    }

    @Test
    public void voiceNeedsGamePeerAndHasNoFourPlayerLimit() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        proxy.startServer();
        proxy.onServerConnectionStatusChanged(
                10, 100, status(10, SteamSocketsApi.STATE_CONNECTING), 0);
        assertFalse(proxy.ownsServerConn(10));
        assertTrue(driver.closed.contains(10));
        for (long peer = 1; peer <= 8; peer++) {
            driver.peers.put(peer, new Object());
            int conn = 100 + (int) peer;
            proxy.onServerConnectionStatusChanged(
                    conn, peer, status(conn, SteamSocketsApi.STATE_CONNECTING), 0);
            assertTrue(proxy.ownsServerConn(conn));
            int duplicate = 200 + (int) peer;
            proxy.onServerConnectionStatusChanged(
                    duplicate, peer, status(duplicate, SteamSocketsApi.STATE_CONNECTING), 0);
            assertFalse(proxy.ownsServerConn(duplicate));
        }
        Object first = driver.peers.get(1L);
        proxy.closeServerPeer(1, first);
        assertFalse(proxy.ownsServerConn(101));
        Object second = new Object();
        driver.peers.put(1L, second);
        proxy.onServerConnectionStatusChanged(
                301, 1, status(301, SteamSocketsApi.STATE_CONNECTING), 0);
        proxy.closeServerPeer(1, first);
        assertTrue(proxy.ownsServerConn(301));
        proxy.stopServer();
        assertTrue(driver.closed.contains(301));
        for (int conn = 102; conn <= 108; conn++) assertTrue(driver.closed.contains(conn));
    }

    @Test
    public void hostRelayRoundTripsIpv4AndIpv6AndRejectsForeignPackets() throws Exception {
        for (String address : new String[] {"127.0.0.1", "::1"}) {
            FakeDriver driver = new FakeDriver();
            SteamUdpProxy proxy = proxy(driver);
            try (DatagramSocket service = new DatagramSocket(new InetSocketAddress(address, 0));
                    DatagramSocket other = new DatagramSocket(new InetSocketAddress(address, 0))) {
                service.setSoTimeout(2000);
                proxy.registerBoundAddress(
                        new InetSocketAddress(
                                InetAddress.getByName(address), service.getLocalPort()));
                UdpHostRelay relay = new UdpHostRelay(proxy, driver, 10, 1);
                assertTrue(relay.start());
                try {
                    for (int length : new int[] {1, 8192, 2, 4096}) {
                        byte[] payload = new byte[length];
                        java.util.Arrays.fill(payload, (byte) length);
                        relay.forwardToLocalService(payload);
                        DatagramPacket request = new DatagramPacket(new byte[65507], 65507);
                        service.receive(request);
                        assertArrayEquals(
                                payload,
                                java.util.Arrays.copyOf(request.getData(), request.getLength()));
                        other.send(
                                new DatagramPacket(new byte[] {99}, 1, request.getSocketAddress()));
                        service.send(
                                new DatagramPacket(
                                        payload, payload.length, request.getSocketAddress()));
                        assertArrayEquals(payload, driver.voicePackets.poll(2, TimeUnit.SECONDS));
                    }
                    assertNull(driver.voicePackets.poll(100, TimeUnit.MILLISECONDS));
                } finally {
                    relay.stop();
                }
                proxy.unregisterBoundPort(service.getLocalPort());
                assertEquals(SteamUdpProxy.DEFAULT_VOICE_PORT, proxy.resolveVoiceTargetPort());
            }
        }
    }

    @Test
    public void wildcardBindResolvesLoopbackInTheSameAddressFamily() throws Exception {
        SteamUdpProxy proxy = proxy(new FakeDriver());
        proxy.registerBoundAddress(new InetSocketAddress("0.0.0.0", 1024));
        assertEquals(InetAddress.getByName("127.0.0.1"), proxy.resolveVoiceTarget().getAddress());
        proxy.registerBoundAddress(new InetSocketAddress("::", 1025));
        assertEquals(InetAddress.getByName("::1"), proxy.resolveVoiceTarget().getAddress());
        proxy.unregisterBoundPort(1024);
        assertEquals(1025, proxy.resolveVoiceTargetPort());
    }

    @Test
    public void gameDisconnectRejectsDelayedVoiceConnectedCallback() throws Exception {
        FakeDriver driver = new FakeDriver();
        SteamUdpProxy proxy = proxy(driver);
        proxy.startServer();
        Object game = new Object();
        driver.peers.put(1L, game);
        proxy.onServerConnectionStatusChanged(
                10, 1, status(10, SteamSocketsApi.STATE_CONNECTING), 0);
        driver.peers.clear();
        proxy.closeServerPeer(1, game);
        proxy.onServerConnectionStatusChanged(
                10, 1, status(10, SteamSocketsApi.STATE_CONNECTED), 1);
        assertFalse(proxy.ownsServerConn(10));
        proxy.stopServer();
    }

    private static final class FakeClient implements VoiceClient {
        final AtomicInteger closed = new AtomicInteger();

        public boolean receiveVoice(byte[] data, InetAddress address, int port) {
            return true;
        }

        public void closeVoice() {
            closed.incrementAndGet();
        }
    }

    private static final class FakeDriver implements VoiceNativeDriver {
        GameAttemptOwner owner;
        int handle;
        final java.util.Map<Long, Object> peers = new java.util.HashMap<>();
        final java.util.List<Integer> closed = new java.util.ArrayList<>();
        final BlockingQueue<byte[]> voicePackets = new LinkedBlockingQueue<>();

        public Object getVoiceSessionToken(long steamId) {
            return peers.get(steamId);
        }

        public int createListenSocketP2P(int port) {
            return 1;
        }

        public boolean closeListenSocket(int handle) {
            return true;
        }

        public boolean acceptConnection(int handle) {
            return true;
        }

        public boolean closeConnection(int handle, int reason, String debug) {
            closed.add(handle);
            return true;
        }

        public int connectP2P(SteamID host, int port, Object owner, ConnectionRegistry.Kind kind) {
            return ++handle;
        }

        public boolean ownsConnectionOwner(int value, Object owner) {
            return value == handle;
        }

        public void sendVoiceBytes(int handle, byte[] data) {
            voicePackets.add(data);
        }

        public GameAttemptOwner getActiveClient() {
            return owner;
        }
    }
}
