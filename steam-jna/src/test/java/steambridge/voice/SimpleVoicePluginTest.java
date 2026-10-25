package steambridge.voice;

import static org.junit.Assert.*;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;

import org.junit.After;
import org.junit.Test;

import steambridge.core.*;

import java.lang.reflect.Proxy;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class SimpleVoicePluginTest {
    private final Map<Class<?>, Consumer<?>> events = new HashMap<Class<?>, Consumer<?>>();

    public SimpleVoicePluginTest() {
        new SimpleVoicePlugin()
                .registerEvents(
                        new EventRegistration() {
                            public <T extends Event> void registerEvent(
                                    Class<T> type, Consumer<T> listener, int priority) {
                                events.put(type, listener);
                            }
                        });
    }

    @After
    public void clearTransport() {
        VoiceIntegration.install(null);
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void fire(Class<T> type, AtomicReference<Object> socket) {
        T event =
                (T)
                        Proxy.newProxyInstance(
                                type.getClassLoader(),
                                new Class<?>[] {type},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getSocketImplementation"))
                                        return socket.get();
                                    if (method.getName().equals("setSocketImplementation")) {
                                        socket.set(args[0]);
                                        return null;
                                    }
                                    throw new AssertionError(
                                            "Unexpected event method: " + method.getName());
                                });
        ((Consumer<T>) events.get(type)).accept(event);
    }

    @Test
    public void ordinaryIpConnectionKeepsDefaultSocket() {
        FakeTransport transport = new FakeTransport();
        transport.active = false;
        VoiceIntegration.install(transport);
        AtomicReference<Object> socket = new AtomicReference<Object>();
        fire(ClientVoicechatInitializationEvent.class, socket);
        assertNull(socket.get());
    }

    @Test
    public void anotherPluginsSocketIsPreserved() {
        VoiceIntegration.install(new FakeTransport());
        Object original =
                Proxy.newProxyInstance(
                        ClientVoicechatSocket.class.getClassLoader(),
                        new Class<?>[] {ClientVoicechatSocket.class},
                        (p, m, a) -> null);
        AtomicReference<Object> socket = new AtomicReference<Object>(original);
        fire(ClientVoicechatInitializationEvent.class, socket);
        assertSame(original, socket.get());
    }

    @Test
    public void clientApiRoundTripsDifferentPacketSizesAndReportsClosed() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        AtomicReference<Object> result = new AtomicReference<Object>();
        fire(ClientVoicechatInitializationEvent.class, result);
        ClientVoicechatSocket socket = (ClientVoicechatSocket) result.get();
        socket.open();
        try {
            for (int length : new int[] {2, 4096, 1, 8192}) {
                byte[] payload = new byte[length];
                Arrays.fill(payload, (byte) length);
                socket.send(payload, new InetSocketAddress("127.0.0.1", 24454));
                RawUdpPacket packet = socket.read();
                assertArrayEquals(payload, packet.getData());
                assertEquals(24454, ((InetSocketAddress) packet.getSocketAddress()).getPort());
                assertTrue(packet.getTimestamp() > 0);
            }
            transport.client.closeVoice();
            assertTrue(socket.isClosed());
        } finally {
            socket.close();
        }
        assertNull(transport.client);
    }

    @Test
    public void serverApiRegistersAssignedPortAndReceivesNormalUdp() throws Exception {
        FakeTransport transport = new FakeTransport();
        VoiceIntegration.install(transport);
        AtomicReference<Object> result = new AtomicReference<Object>();
        fire(VoicechatServerStartingEvent.class, result);
        VoicechatSocket socket = (VoicechatSocket) result.get();
        socket.open(0, "127.0.0.1");
        try (DatagramSocket sender = new DatagramSocket()) {
            assertTrue(socket.getLocalPort() > 0);
            assertEquals(socket.getLocalPort(), transport.port);
            for (int length : new int[] {1, 4096, 2}) {
                byte[] payload = new byte[length];
                sender.send(
                        new DatagramPacket(
                                payload,
                                length,
                                InetAddress.getByName("127.0.0.1"),
                                socket.getLocalPort()));
                assertArrayEquals(payload, socket.read().getData());
            }
        } finally {
            socket.close();
        }
        assertTrue(socket.isClosed());
    }

    private static final class FakeTransport implements VoiceTransport {
        boolean active = true;
        VoiceClient client;
        int port;

        public boolean isClientActive() {
            return active;
        }

        public VoiceClient voiceClient() {
            return client;
        }

        public boolean claimVoiceClient(VoiceClient value, int port) {
            if (client != null) return false;
            client = value;
            this.port = port;
            return true;
        }

        public void releaseVoiceClient(VoiceClient value) {
            if (client == value) client = null;
        }

        public void sendFromClientImpl(byte[] data) {
            try {
                client.receiveVoice(data, InetAddress.getByName("127.0.0.1"), port);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        public void registerBoundPort(int value) {
            port = value;
        }
    }
}
