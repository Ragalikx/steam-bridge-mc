/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;
import steambridge.steam.SteamManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side per-client UDP relay.
 *
 * Binds an ephemeral local UDP socket and acts as a fake local client talking to whatever
 * voice service is running on this machine (Simple Voice Chat, Plasmo Voice, etc.).
 * The target port is resolved via {@link SteamUdpProxy#resolveVoiceTargetPort()}
 * (detected explicit bind -> host game port -> 24454).
 */
final class UdpHostRelay {

    private static final int BUF_SIZE = 4096;

    private final int steamConn;
    private final long steamID;
    private volatile DatagramSocket socket;
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
            socket = new DatagramSocket();
        } catch (Exception e) {
            SteamBridgeMod.LOG.error("[UdpRelay] Failed to open socket for conn={}: {}", steamConn, e.getMessage());
            running = false;
            return;
        }
        thread = new Thread(this::receiveLoop, "SteamBridge-UdpRelay-" + steamConn);
        thread.setDaemon(true);
        thread.start();
        SteamBridgeMod.LOG.info(
            "[UdpRelay] Ready conn={} steamID={} localEphemeral={} voiceTarget={}",
            steamConn, steamID, socket.getLocalPort(), SteamUdpProxy.getInstance().describeVoiceTarget()
        );
    }

    void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    long packetsFromSteam() { return fromSteam.get(); }
    long packetsToSteam() { return toSteam.get(); }

    void forwardToLocalService(byte[] data) {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) return;
        try {
            SteamUdpProxy proxy = SteamUdpProxy.getInstance();
            int port = proxy.resolveVoiceTargetPort();
            if (!loggedFirstForward || port != lastTargetPort) {
                loggedFirstForward = true;
                lastTargetPort = port;
                SteamBridgeMod.LOG.info(
                    "[UdpRelay] Forward steam->svc conn={} steamID={} target={} bytes={}",
                    steamConn, steamID, proxy.describeVoiceTarget(), data != null ? data.length : 0
                );
            }
            fromSteam.incrementAndGet();
            s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
        } catch (Exception e) {
            if (running) {
                SteamBridgeMod.LOG.warn(
                    "[UdpRelay] Forward to service failed conn={} target={}: {}",
                    steamConn, SteamUdpProxy.getInstance().describeVoiceTarget(), e.getMessage()
                );
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[BUF_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                long n = toSteam.incrementAndGet();
                SteamUdpProxy.getInstance().noteServerVoiceOut();
                if (n == 1) {
                    SteamBridgeMod.LOG.info(
                        "[UdpRelay] First svc->steam reply conn={} steamID={} bytes={}",
                        steamConn, steamID, data.length
                    );
                }
                SteamManager.getInstance().sendVoiceBytes(steamConn, data);
            } catch (java.net.SocketException e) {
                break;
            } catch (Exception e) {
                if (running) SteamBridgeMod.LOG.warn("[UdpRelay] Recv error conn={}: {}", steamConn, e.getMessage());
            }
        }
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) s.close();
    }
}
