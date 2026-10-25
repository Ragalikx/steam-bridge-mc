package steambridge.voice;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;

import steambridge.core.VoiceDatagramSocket;
import steambridge.core.VoiceTransport;

import java.net.*;

@ForgeVoicechatPlugin
public final class SimpleVoicePlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "steambridge";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        try {
            Class.forName(
                    "de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent",
                    false,
                    getClass().getClassLoader());
            registerClient(registration);
        } catch (ClassNotFoundException olderApi) {
        }
        registration.registerEvent(
                VoicechatServerStartingEvent.class,
                event -> {
                    VoiceTransport transport = VoiceIntegration.transport();
                    if (transport != null && event.getSocketImplementation() == null)
                        event.setSocketImplementation(new ServerSocket(transport));
                });
    }

    private void registerClient(EventRegistration registration) {
        registration.registerEvent(
                ClientVoicechatInitializationEvent.class,
                event -> {
                    VoiceTransport transport = VoiceIntegration.transport();
                    if (transport != null
                            && transport.isClientActive()
                            && event.getSocketImplementation() == null)
                        event.setSocketImplementation(new ClientSocket(transport));
                });
    }

    private static class SocketBase {
        final VoiceTransport transport;
        volatile VoiceDatagramSocket socket;
        private final ThreadLocal<DatagramPacket> incoming =
                new ThreadLocal<DatagramPacket>() {
                    @Override
                    protected DatagramPacket initialValue() {
                        return new DatagramPacket(new byte[65507], 65507);
                    }
                };

        SocketBase(VoiceTransport transport) {
            this.transport = transport;
        }

        public RawUdpPacket read() throws Exception {
            VoiceDatagramSocket active = socket;
            if (active == null) throw new SocketException("Voice socket is closed");
            DatagramPacket packet = incoming.get();
            packet.setLength(65507);
            active.receive(packet);
            final byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
            final SocketAddress source = packet.getSocketAddress();
            final long receivedAt = System.currentTimeMillis();
            return new RawUdpPacket() {
                public byte[] getData() {
                    return data;
                }

                public long getTimestamp() {
                    return receivedAt;
                }

                public SocketAddress getSocketAddress() {
                    return source;
                }
            };
        }

        public void send(byte[] data, SocketAddress address) throws Exception {
            VoiceDatagramSocket active = socket;
            if (active == null) throw new SocketException("Voice socket is closed");
            active.send(new DatagramPacket(data, data.length, address));
        }

        public synchronized void close() {
            if (socket != null) socket.close();
            socket = null;
        }

        public boolean isClosed() {
            return socket == null || socket.isClosed();
        }
    }

    private static final class ClientSocket extends SocketBase implements ClientVoicechatSocket {
        ClientSocket(VoiceTransport transport) {
            super(transport);
        }

        public synchronized void open() throws Exception {
            if (socket != null) throw new SocketException("Voice socket already opened");
            VoiceDatagramSocket fresh = new VoiceDatagramSocket(transport, false);
            try {
                fresh.bind(new InetSocketAddress(0));
                socket = fresh;
            } catch (Exception failure) {
                fresh.close();
                throw failure;
            }
        }
    }

    private static final class ServerSocket extends SocketBase implements VoicechatSocket {
        ServerSocket(VoiceTransport transport) {
            super(transport);
        }

        public synchronized void open(int port, String bindAddress) throws Exception {
            if (socket != null) throw new SocketException("Voice socket already opened");
            VoiceDatagramSocket fresh = new VoiceDatagramSocket(transport, true);
            try {
                InetAddress address =
                        (bindAddress.isEmpty() || bindAddress.equals("*"))
                                ? null
                                : InetAddress.getByName(bindAddress);
                fresh.bind(new InetSocketAddress(address, port));
                transport.registerBoundAddress(
                        this, new InetSocketAddress(address, fresh.getLocalPort()));
                socket = fresh;
            } catch (Exception failure) {
                fresh.close();
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            int port = getLocalPort();
            super.close();
            if (port > 0) transport.unregisterBoundAddress(this, port);
        }

        public int getLocalPort() {
            return socket == null ? -1 : socket.getLocalPort();
        }
    }
}
