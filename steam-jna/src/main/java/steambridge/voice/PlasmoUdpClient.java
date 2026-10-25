package steambridge.voice;

import steambridge.core.LocalVoiceRelay;

import su.plo.voice.api.client.event.socket.UdpClientClosedEvent;
import su.plo.voice.api.client.socket.UdpClient;
import su.plo.voice.proto.packets.Packet;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

public final class PlasmoUdpClient implements UdpClient {
    private final UdpClient delegate;
    private LocalVoiceRelay relay;
    private boolean closed;

    PlasmoUdpClient(UdpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public void connect(String host, int port) {
        LocalVoiceRelay opened;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Voice client is closed");
            if (relay != null) throw new IllegalStateException("Voice client already connected");
            opened = PlasmoVoiceBridge.connect(host, port);
            relay = opened;
        }
        try {
            delegate.connect(
                    opened == null ? host : "127.0.0.1", opened == null ? port : opened.port());
            boolean stopped;
            synchronized (this) {
                stopped = closed;
            }
            if (stopped) delegate.close(UdpClientClosedEvent.Reason.DISCONNECT);
        } catch (RuntimeException | Error failure) {
            close(UdpClientClosedEvent.Reason.FAILED_TO_CONNECT);
            throw failure;
        }
    }

    synchronized void closeRelay() {
        closed = true;
        if (relay != null) relay.closeVoice();
        relay = null;
    }

    boolean isDelegate(UdpClient client) {
        return delegate == client || this == client;
    }

    @Override
    public void close(UdpClientClosedEvent.Reason reason) {
        closeRelay();
        delegate.close(reason);
    }

    @Override
    public void sendPacket(Packet<?> packet) {
        delegate.sendPacket(packet);
    }

    @Override
    public UUID getSecret() {
        return delegate.getSecret();
    }

    @Override
    public Optional<InetSocketAddress> getRemoteAddress() {
        return delegate.getRemoteAddress();
    }

    @Override
    public synchronized boolean isClosed() {
        return closed || delegate.isClosed();
    }

    @Override
    public boolean isConnected() {
        return !isClosed() && delegate.isConnected();
    }

    @Override
    public boolean isTimedOut() {
        return delegate.isTimedOut();
    }

    @Override
    public long getKeepAlive() {
        return delegate.getKeepAlive();
    }
}
