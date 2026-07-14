/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;

import java.io.IOException;
import java.net.DatagramSocketImpl;
import java.net.DatagramSocketImplFactory;

public final class UdpInterceptFactory implements DatagramSocketImplFactory {

    private static volatile boolean installed = false;

    public static void install() {
        if (installed) return;
        try {
            java.net.DatagramSocket.setDatagramSocketImplFactory(new UdpInterceptFactory());
            installed = true;
            SteamBridgeMod.LOG.info(
                "[UdpProxy] DatagramSocket factory installed - UDP sockets will be intercepted.");
        } catch (IOException e) {
            SteamBridgeMod.LOG.warn(
                "[UdpProxy] Failed to install DatagramSocket factory: {}. "
                + "Another mod may have claimed it first. Voice tunnelling is unavailable.",
                e.getMessage());
        }
    }

    public static boolean isInstalled() { return installed; }

    @Override
    public DatagramSocketImpl createDatagramSocketImpl() {
        return new SteamAwareDatagramImpl();
    }
}
