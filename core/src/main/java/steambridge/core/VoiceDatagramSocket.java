package steambridge.core;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public final class VoiceDatagramSocket extends DatagramSocket {
    private final SteamDatagramSocketImpl delegate;

    public VoiceDatagramSocket(final VoiceTransport transport, final boolean server)
            throws SocketException {
        this(endpoint(transport, server));
    }

    private VoiceDatagramSocket(SteamDatagramSocketImpl delegate) throws SocketException {
        super(delegate);
        this.delegate = delegate;
    }

    @Override
    public boolean isClosed() {
        return super.isClosed() || (delegate != null && delegate.isEndpointClosed());
    }

    private static SteamDatagramSocketImpl endpoint(
            final VoiceTransport transport, final boolean server) {
        return new SteamDatagramSocketImpl(
                new SteamDatagramSocketImpl.Endpoint() {
                    public boolean intercept(InetAddress address, int port) {
                        return !server
                                && transport.isClientActive()
                                && transport.voiceClient() == null;
                    }

                    public boolean activated(SteamDatagramSocketImpl socket, int port) {
                        return transport.claimVoiceClient(socket, port);
                    }

                    public void closed(SteamDatagramSocketImpl socket) {
                        transport.releaseVoiceClient(socket);
                    }

                    public void send(byte[] data) {
                        transport.sendFromClientImpl(data);
                    }

                    public void send(SteamDatagramSocketImpl owner, byte[] data) {
                        transport.sendFromClient(owner, data);
                    }

                    public void bound(int port) {
                        if (server) transport.registerBoundPort(port);
                    }
                });
    }
}
