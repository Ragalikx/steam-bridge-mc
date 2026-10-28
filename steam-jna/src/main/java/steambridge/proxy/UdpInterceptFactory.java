/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.core.LogLimiter;
import steambridge.core.ProblemLog;

import java.io.IOException;
import java.net.DatagramSocketImpl;
import java.net.DatagramSocketImplFactory;

public final class UdpInterceptFactory implements DatagramSocketImplFactory {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    private static volatile boolean installed = false;

    public static void install() {
        if (installed) return;
        try {
            java.net.DatagramSocket.setDatagramSocketImplFactory(new UdpInterceptFactory());
            installed = true;
            LOG.debug(
                    "[UdpProxy] DatagramSocket factory installed - voice UDP sockets can use Steam"
                            + " tunnelling.");
        } catch (IOException e) {
            ProblemLog.warn(
                    LOG,
                    LOG_LIMITER,
                    "UdpInterceptFactory-warn-1",
                    "[UdpProxy] Failed to install DatagramSocket factory: {}. Another mod may have"
                            + " claimed it first. Voice tunnelling is unavailable.",
                    e.getMessage());
        }
    }

    @Override
    public DatagramSocketImpl createDatagramSocketImpl() {
        return new SteamAwareDatagramImpl(steambridge.core.VoiceSocketPolicy.createdByVoiceMod());
    }
}
