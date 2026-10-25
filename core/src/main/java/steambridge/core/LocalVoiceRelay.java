package steambridge.core;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalVoiceRelay implements VoiceClient {
    private final VoiceTransport transport;
    private final DatagramSocket socket;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SocketAddress peer;

    private LocalVoiceRelay(VoiceTransport transport) throws IOException {
        this.transport = transport;
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    }

    public static LocalVoiceRelay open(VoiceTransport transport, int port) throws IOException {
        if (transport == null || !transport.isClientActive()) return null;
        if (port < 1 || port > 65535) throw new SocketException("Invalid voice port");
        LocalVoiceRelay relay = new LocalVoiceRelay(transport);
        if (!transport.claimVoiceClient(relay, port)) {
            relay.closeVoice();
            return null;
        }
        try {
            Thread worker = new Thread(relay::read, "SteamBridge-VoiceClient");
            worker.setDaemon(true);
            worker.start();
            return relay;
        } catch (RuntimeException failure) {
            relay.closeVoice();
            throw failure;
        }
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void read() {
        byte[] buffer = new byte[65507];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            while (!closed.get()) {
                packet.setLength(buffer.length);
                socket.receive(packet);
                if (!packet.getAddress().isLoopbackAddress()) continue;
                SocketAddress source = packet.getSocketAddress();
                if (peer == null) peer = source;
                if (peer.equals(source))
                    transport.sendFromClient(this, Arrays.copyOf(buffer, packet.getLength()));
            }
        } catch (IOException stopped) {
            if (!closed.get()) closeVoice();
        } finally {
            closeVoice();
        }
    }

    @Override
    public boolean receiveVoice(byte[] data, InetAddress address, int port) {
        SocketAddress destination = peer;
        if (closed.get() || destination == null || data == null || data.length > 65507)
            return false;
        try {
            socket.send(new DatagramPacket(data, data.length, destination));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    @Override
    public void closeVoice() {
        if (!closed.compareAndSet(false, true)) return;
        socket.close();
        transport.releaseVoiceClient(this);
    }
}
