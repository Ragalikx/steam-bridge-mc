package steambridge.voice;

import steambridge.core.VoiceTransport;

import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.event.socket.UdpServerStartedEvent;
import su.plo.voice.api.server.event.socket.UdpServerStoppedEvent;
import su.plo.voice.api.server.socket.UdpServer;

@Addon(
        id = "steambridge-server",
        name = "Steam Bridge",
        version = "1",
        authors = {"Ragalikx"})
public final class PlasmoServerAddon {
    private UdpServer active;
    private int port;
    private VoiceTransport transport;

    @EventSubscribe
    public synchronized void started(UdpServerStartedEvent event) {
        VoiceTransport current = VoiceIntegration.transport();
        if (current == null || !event.getServer().getRemoteAddress().isPresent()) return;
        if (transport != null) transport.unregisterBoundAddress(active, port);
        active = event.getServer();
        port = active.getRemoteAddress().get().getPort();
        transport = current;
        current.registerBoundAddress(active, active.getRemoteAddress().get());
    }

    @EventSubscribe
    public synchronized void stopped(UdpServerStoppedEvent event) {
        if (active != event.getServer()) return;
        if (transport != null) transport.unregisterBoundAddress(active, port);
        active = null;
        transport = null;
        port = 0;
    }
}
