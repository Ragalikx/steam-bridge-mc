package steambridge.voice;

import org.apache.logging.log4j.LogManager;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlasmoIntegration {
    private static final AtomicBoolean CLIENT = new AtomicBoolean();
    private static final AtomicBoolean SERVER = new AtomicBoolean();

    private PlasmoIntegration() {}

    public static void install() {
        ClassLoader loader = PlasmoIntegration.class.getClassLoader();
        try {
            Class.forName(
                    "su.plo.voice.api.client.event.socket.UdpClientConnectEvent", false, loader);
            if (CLIENT.compareAndSet(false, true)) ClientRegistration.load();
        } catch (ClassNotFoundException absent) {
        } catch (LinkageError | RuntimeException failure) {
            LogManager.getLogger("steambridge").warn("Plasmo client API unavailable", failure);
        }
        try {
            Class.forName(
                    "su.plo.voice.api.server.event.socket.UdpServerStartedEvent", false, loader);
            if (SERVER.compareAndSet(false, true)) ServerRegistration.load();
        } catch (ClassNotFoundException absent) {
        } catch (LinkageError | RuntimeException failure) {
            LogManager.getLogger("steambridge").warn("Plasmo server API unavailable", failure);
        }
    }

    private static final class ClientRegistration {
        static void load() {
            su.plo.voice.api.client.PlasmoVoiceClient.getAddonsLoader()
                    .load(new PlasmoClientAddon());
        }
    }

    private static final class ServerRegistration {
        static void load() {
            su.plo.voice.api.server.PlasmoVoiceServer.getAddonsLoader()
                    .load(new PlasmoServerAddon());
        }
    }
}
