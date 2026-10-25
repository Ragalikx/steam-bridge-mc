package steambridge.proxy;

import steambridge.core.SteamDatagramSocketImpl;

import java.net.InetAddress;

final class SteamAwareDatagramImpl extends SteamDatagramSocketImpl {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    SteamAwareDatagramImpl(final boolean voiceSocket) {
        super(
                new Endpoint() {
                    public boolean intercept(InetAddress address, int port) {
                        SteamUdpProxy proxy = SteamUdpProxy.getInstance();
                        return voiceSocket
                                && proxy.isClientActive()
                                && proxy.getActiveClientImpl() == null;
                    }

                    public boolean activated(SteamDatagramSocketImpl socket, int port) {
                        return SteamUdpProxy.getInstance().claimVoiceClient(socket, port);
                    }

                    public void closed(SteamDatagramSocketImpl socket) {
                        SteamUdpProxy proxy = SteamUdpProxy.getInstance();
                        proxy.clearActiveClientImpl((SteamAwareDatagramImpl) socket);
                    }

                    public void send(byte[] data) {
                        SteamUdpProxy.getInstance().sendFromClientImpl(data);
                    }

                    public void send(steambridge.core.SteamDatagramSocketImpl owner, byte[] data) {
                        SteamUdpProxy.getInstance().sendFromClient(owner, data);
                    }

                    public void bound(int port) {
                        if (voiceSocket) SteamUdpProxy.getInstance().registerBoundPort(port);
                    }
                });
    }
}
