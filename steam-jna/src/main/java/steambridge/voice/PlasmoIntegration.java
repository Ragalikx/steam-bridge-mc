package steambridge.voice;

import org.apache.logging.log4j.LogManager;

import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlasmoIntegration {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

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
            ProblemLog.warn(
                    LogManager.getLogger("steambridge"),
                    LOG_LIMITER,
                    "PlasmoIntegration-warn-1",
                    "Plasmo client API unavailable",
                    failure);
        }
        try {
            Class.forName(
                    "su.plo.voice.api.server.event.socket.UdpServerStartedEvent", false, loader);
            if (SERVER.compareAndSet(false, true)) ServerRegistration.load();
        } catch (ClassNotFoundException absent) {
        } catch (LinkageError | RuntimeException failure) {
            ProblemLog.warn(
                    LogManager.getLogger("steambridge"),
                    LOG_LIMITER,
                    "PlasmoIntegration-warn-2",
                    "Plasmo server API unavailable",
                    failure);
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
