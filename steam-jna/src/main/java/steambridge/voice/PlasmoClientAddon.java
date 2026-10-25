package steambridge.voice;

import steambridge.core.VoiceTransport;

import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.event.socket.UdpClientClosedEvent;
import su.plo.voice.api.client.event.socket.UdpClientConnectEvent;
import su.plo.voice.api.client.socket.UdpClient;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.event.EventSubscribe;

import java.util.function.Predicate;

@Addon(
        id = "steambridge-client",
        name = "Steam Bridge",
        version = "1",
        authors = {"Ragalikx"})
public final class PlasmoClientAddon {
    private final Predicate<UdpClient> canWrap;
    private PlasmoUdpClient active;

    public PlasmoClientAddon() {
        this(
                client ->
                        client.getClass()
                                .getName()
                                .equals("su.plo.voice.client.socket.NettyUdpClient"));
    }

    PlasmoClientAddon(Predicate<UdpClient> canWrap) {
        this.canWrap = canWrap;
    }

    @EventSubscribe(priority = EventPriority.HIGHEST)
    public synchronized void connecting(UdpClientConnectEvent event) {
        VoiceTransport transport = VoiceIntegration.transport();
        if (event.isCancelled() || transport == null || !transport.isClientActive()) return;
        if (event.getClient() instanceof PlasmoUdpClient) return;
        if (!canWrap.test(event.getClient())) return;
        if (active != null) active.closeRelay();
        active = new PlasmoUdpClient(event.getClient());
        event.setClient(active);
    }

    @EventSubscribe
    public synchronized void closed(UdpClientClosedEvent event) {
        if (active != null && active.isDelegate(event.getClient())) {
            active.closeRelay();
            active = null;
        }
    }
}
