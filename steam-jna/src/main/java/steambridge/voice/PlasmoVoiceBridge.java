package steambridge.voice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import steambridge.core.LocalVoiceRelay;
import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;
import steambridge.core.VoiceTransport;

import java.net.InetAddress;

public final class PlasmoVoiceBridge {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final Logger LOG = LogManager.getLogger("steambridge");

    private PlasmoVoiceBridge() {}

    public static LocalVoiceRelay connect(String host, int port) {
        VoiceTransport transport = VoiceIntegration.transport();
        if (transport == null || !transport.isClientActive()) return null;
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) return null;
            LocalVoiceRelay relay = LocalVoiceRelay.open(transport, port);
            if (relay == null)
                ProblemLog.warn(
                        LOG,
                        LOG_LIMITER,
                        "PlasmoVoiceBridge-warn-1",
                        "Plasmo voice tunnel is already occupied");
            return relay;
        } catch (Exception failure) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "PlasmoVoiceBridge-warn-2",
                    "Plasmo voice tunnel unavailable: {}",
                    steambridge.core.LogText.safe(failure.getMessage()));
            return null;
        }
    }
}
