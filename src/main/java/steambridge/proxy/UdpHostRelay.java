/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side per-client UDP relay.
 *
 * Binds an ephemeral local UDP socket and acts as a fake local client talking to
 * the voice service on this machine. Target port:
 * {@link SteamUdpProxy#resolveVoiceTargetPort()}.
 *
 * <p>Uses {@link DatagramChannel} so traffic bypasses {@link UdpInterceptFactory}
 * (relay must not share the client voice intercept path).
 */
final class UdpHostRelay {

    private static final int BUF_SIZE = 4096;

    private final int steamConn;
    private final long steamID;
    private volatile DatagramChannel channel;
    private volatile Thread thread;
    private volatile boolean running;

    private final AtomicLong fromSteam = new AtomicLong();
    private final AtomicLong toSteam = new AtomicLong();
    private volatile boolean loggedFirstForward;
    private volatile int lastTargetPort;

    UdpHostRelay(int steamConn, long steamID) {
        this.steamConn = steamConn;
        this.steamID = steamID;
    }

    void start() {
        running = true;
        try {
            DatagramChannel ch = DatagramChannel.open();
            ch.configureBlocking(true);
            ch.bind(new InetSocketAddress(0));
            channel = ch;
        } catch (Exception e) {
            SteamBridgeMod.LOG.error("[UdpRelay] Failed to open socket for conn={}: {}", steamConn, e.toString());
            running = false;
            return;
        }
        thread = new Thread(this::receiveLoop, "SteamBridge-UdpRelay-" + steamConn);
        thread.setDaemon(true);
        thread.start();
        int localPort = -1;
        try {
            SocketAddress local = channel.getLocalAddress();
            if (local instanceof InetSocketAddress) {
                localPort = ((InetSocketAddress) local).getPort();
            }
        } catch (Exception ignored) {}
        SteamBridgeMod.LOG.info(
            "[UdpRelay] Ready conn={} steamID={} localEphemeral={} voiceTarget={}",
            steamConn, steamID, localPort, SteamUdpProxy.getInstance().describeVoiceTarget()
        );
    }

    void stop() {
        running = false;
        DatagramChannel ch = channel;
        if (ch != null) {
            try { ch.close(); } catch (Exception ignored) {}
        }
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    long packetsFromSteam() { return fromSteam.get(); }
    long packetsToSteam() { return toSteam.get(); }

    void forwardToLocalService(byte[] data) {
        DatagramChannel ch = channel;
        if (ch == null || !ch.isOpen() || data == null || data.length == 0) return;
        try {
            SteamUdpProxy proxy = SteamUdpProxy.getInstance();
            int port = proxy.resolveVoiceTargetPort();
            if (!loggedFirstForward || port != lastTargetPort) {
                loggedFirstForward = true;
                lastTargetPort = port;
                SteamBridgeMod.LOG.info(
                    "[UdpRelay] Forward steam->svc conn={} steamID={} target={} bytes={}",
                    steamConn, steamID, proxy.describeVoiceTarget(), data.length
                );
            }
            fromSteam.incrementAndGet();
            ch.send(ByteBuffer.wrap(data), new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        } catch (Exception e) {
            if (running) {
                SteamBridgeMod.LOG.warn(
                    "[UdpRelay] Forward to service failed conn={} target={}: {}",
                    steamConn, SteamUdpProxy.getInstance().describeVoiceTarget(), e.toString()
                );
            }
        }
    }

    private void receiveLoop() {
        ByteBuffer buf = ByteBuffer.allocate(BUF_SIZE);
        while (running) {
            DatagramChannel ch = channel;
            if (ch == null || !ch.isOpen()) break;
            try {
                buf.clear();
                SocketAddress src = ch.receive(buf);
                if (src == null) continue;
                buf.flip();
                int len = buf.remaining();
                if (len <= 0) continue;
                byte[] data = new byte[len];
                buf.get(data);
                long n = toSteam.incrementAndGet();
                SteamUdpProxy.getInstance().noteServerVoiceOut();
                if (n == 1) {
                    SteamBridgeMod.LOG.info(
                        "[UdpRelay] First svc->steam reply conn={} steamID={} bytes={}",
                        steamConn, steamID, data.length
                    );
                }
                SteamManager.getInstance().sendVoiceBytes(steamConn, data);
            } catch (ClosedChannelException e) {
                break;
            } catch (java.net.SocketException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    SteamBridgeMod.LOG.warn(
                        "[UdpRelay] Recv error conn={}: {}", steamConn, e.toString()
                    );
                }
            }
        }
        DatagramChannel ch = channel;
        if (ch != null && ch.isOpen()) {
            try { ch.close(); } catch (Exception ignored) {}
        }
    }
}
