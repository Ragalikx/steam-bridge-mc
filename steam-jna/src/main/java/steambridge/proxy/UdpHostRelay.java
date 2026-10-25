/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/** Host-side per-client UDP relay to local voice service. */
final class UdpHostRelay {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");

    private static final int BUF_SIZE = 65536;

    private final SteamUdpProxy proxy;
    private final steambridge.voice.VoiceNativeDriver driver;
    private final int steamConn;
    private final long steamID;
    private volatile DatagramSocket socket;
    private volatile Thread thread;
    private volatile boolean running;
    private boolean stopped;

    private boolean loggedFirstReply;
    private volatile boolean loggedFirstForward;
    private volatile int lastTargetPort;

    UdpHostRelay(
            SteamUdpProxy proxy,
            steambridge.voice.VoiceNativeDriver driver,
            int steamConn,
            long steamID) {
        this.proxy = proxy;
        this.driver = driver;
        this.steamConn = steamConn;
        this.steamID = steamID;
    }

    synchronized boolean start() {
        if (stopped || running) return running;
        running = true;
        try {
            socket =
                    new DatagramSocket(
                            new java.net.InetSocketAddress(
                                    proxy.resolveVoiceTarget().getAddress(), 0));
        } catch (Exception e) {
            LOG.error(
                    "[UdpRelay] Failed to open socket for conn={}: {}", steamConn, e.getMessage());
            running = false;
            return false;
        }
        thread = new Thread(this::receiveLoop, "SteamBridge-UdpRelay-" + steamConn);
        thread.setDaemon(true);
        thread.start();
        LOG.info(
                "[UdpRelay] Ready conn={} steamID={} localEphemeral={} voiceTarget={}",
                steamConn,
                steamID,
                socket.getLocalPort(),
                proxy.describeVoiceTarget());
        return true;
    }

    synchronized void stop() {
        stopped = true;
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    void forwardToLocalService(byte[] data) {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) return;
        try {
            java.net.InetSocketAddress target = proxy.resolveVoiceTarget();
            int port = target.getPort();
            if (!loggedFirstForward || port != lastTargetPort) {
                loggedFirstForward = true;
                lastTargetPort = port;
                LOG.info(
                        "[UdpRelay] Forward steam->svc conn={} steamID={} target={} bytes={}",
                        steamConn,
                        steamID,
                        proxy.describeVoiceTarget(),
                        data != null ? data.length : 0);
            }
            s.send(new DatagramPacket(data, data.length, target));
        } catch (Exception e) {
            if (running) {
                LOG.warn(
                        "[UdpRelay] Forward to service failed conn={} target={}: {}",
                        steamConn,
                        proxy.describeVoiceTarget(),
                        e.getMessage());
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[BUF_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                if (!packet.getSocketAddress().equals(proxy.resolveVoiceTarget())) continue;
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                proxy.noteServerVoiceOut();
                if (!loggedFirstReply) {
                    loggedFirstReply = true;
                    LOG.info(
                            "[UdpRelay] First svc->steam reply conn={} steamID={} bytes={}",
                            steamConn,
                            steamID,
                            data.length);
                }
                forwardReply(data);
            } catch (java.net.SocketException e) {
                break;
            } catch (Exception e) {
                if (running)
                    LOG.warn("[UdpRelay] Recv error conn={}: {}", steamConn, e.getMessage());
            }
        }
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) s.close();
    }

    private synchronized void forwardReply(byte[] data) {
        if (running && !stopped) driver.sendVoiceBytes(steamConn, data);
    }
}
