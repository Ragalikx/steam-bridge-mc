/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import com.codedisaster.steamworks.SteamID;
import steambridge.SteamBridgeConfig;
import steambridge.SteamBridgeMod;
import steambridge.steam.SteamConnectionStatus;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamSocketsApi;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tunnels arbitrary UDP traffic over Steam virtual port (MC port + 1).
 *
 * <h3>Port detection</h3>
 * Port discovery is handled by {@link UdpInterceptFactory} / {@link SteamAwareDatagramImpl}:
 * <ul>
 *   <li>Host side: when a voice mod binds an <b>explicit</b> UDP port (e.g. SVC
 *       {@code changePort(gamePort)} / dedicated config), {@link #registerBoundPort} records it
 *       for {@link UdpHostRelay}. OS-assigned {@code bind(0)} is ignored (client sockets,
 *       relay sockets). Fallback chain: detected bind -> host game/LAN port -> 24454.</li>
 *   <li>Client side: when SVC sends its first packet to {@code 127.0.0.1:<voicePort>},
 *       {@link SteamAwareDatagramImpl} switches to steam mode, records the server port
 *       via {@link #setServerVoicePort}, and registers itself as the active receive sink.</li>
 * </ul>
 */
public final class SteamUdpProxy {

    private static final SteamUdpProxy INSTANCE = new SteamUdpProxy();
    static final int DEFAULT_VOICE_PORT = 24454;

    public static SteamUdpProxy getInstance() { return INSTANCE; }

    // -- Server state ----------------------------------------------------------

    private volatile int udpListenSocket = 0;
    private final Map<Integer, UdpHostRelay> relayByConn = new ConcurrentHashMap<>();
    private final Set<Integer> acceptedConns = ConcurrentHashMap.newKeySet();

    // Explicit voice-server bind (SVC changePort / dedicated config). Not OS-assigned sockets.
    private final AtomicInteger knownVoiceServerPort = new AtomicInteger(0);
    // Minecraft LAN/game port - SVC on integrated host rebinds voice to this after publish.
    private final AtomicInteger hostGamePort = new AtomicInteger(0);

    private final AtomicLong serverVoicePacketsIn = new AtomicLong();
    private final AtomicLong serverVoicePacketsOut = new AtomicLong();
    private final AtomicLong clientVoicePacketsIn = new AtomicLong();
    private final AtomicLong clientVoicePacketsOut = new AtomicLong();

    // -- Client state ----------------------------------------------------------

    private volatile int udpClientConn = 0;
    private volatile SteamAwareDatagramImpl activeClientImpl;
    private volatile int serverVoicePort = 0;
    private volatile boolean loggedMissingClientImpl;
    private volatile boolean loggedMissingClientConn;

    private SteamUdpProxy() {}

    // -- Server-side API -------------------------------------------------------

    public void startServer() {
        int udpVirtualPort = SteamBridgeConfig.virtualPort + 1;
        serverVoicePacketsIn.set(0);
        serverVoicePacketsOut.set(0);
        udpListenSocket = SteamManager.getInstance().createListenSocketP2P(udpVirtualPort);
        if (udpListenSocket == 0) {
            SteamBridgeMod.LOG.warn("[UdpProxy] Failed to open listen socket on virtual port {}.", udpVirtualPort);
        } else {
            SteamBridgeMod.LOG.info("[UdpProxy] Listen socket open: handle={} virtualPort={}", udpListenSocket, udpVirtualPort);
        }
        SteamBridgeMod.LOG.info(
            "[UdpProxy] Voice target: detected={} gamePort={} resolved={}",
            knownVoiceServerPort.get(), hostGamePort.get(), resolveVoiceTargetPort()
        );
    }

    public void stopServer() {
        int ls = udpListenSocket;
        udpListenSocket = 0;
        acceptedConns.clear();
        knownVoiceServerPort.set(0);
        hostGamePort.set(0);
        for (Map.Entry<Integer, UdpHostRelay> e : relayByConn.entrySet()) {
            e.getValue().stop();
            SteamManager.getInstance().closeConnection(e.getKey(), SteamSocketsApi.APP_CLOSE_NORMAL, "UDP proxy stopped");
        }
        relayByConn.clear();
        if (ls != 0) SteamManager.getInstance().closeListenSocket(ls);
        SteamBridgeMod.LOG.info(
            "[UdpProxy] Server stopped. voicePkts in={} out={}",
            serverVoicePacketsIn.get(), serverVoicePacketsOut.get()
        );
    }

    public boolean ownsListenSocket(int listenSocket) {
        return udpListenSocket != 0 && listenSocket == udpListenSocket;
    }

    public boolean ownsServerConn(int conn) {
        return relayByConn.containsKey(conn) || acceptedConns.contains(conn);
    }

    public void onServerConnectionStatusChanged(int conn, long steamID, SteamConnectionStatus status, int oldState) {
        SteamBridgeMod.LOG.info(
            "[UdpProxy] Server conn={} steamID={} state={} route={} oldState={}",
            conn, steamID, status.describeState(), status.describeRoute(),
            SteamSocketsApi.stateName(oldState)
        );

        if (status.getState() == SteamSocketsApi.STATE_CONNECTING
                || status.getState() == SteamSocketsApi.STATE_FINDING_ROUTE) {
            if (acceptedConns.add(conn)) {
                boolean accepted = SteamManager.getInstance().acceptConnection(conn);
                if (!accepted) {
                    acceptedConns.remove(conn);
                    SteamManager.getInstance().closeConnection(conn, SteamSocketsApi.APP_CLOSE_LOCAL_ERROR, "UDP proxy accept failed");
                    SteamBridgeMod.LOG.warn("[UdpProxy] Accept failed for conn={} steamID={}", conn, steamID);
                }
            }
            return;
        }

        if (status.getState() == SteamSocketsApi.STATE_CONNECTED) {
            UdpHostRelay relay = new UdpHostRelay(conn, steamID);
            relayByConn.put(conn, relay);
            relay.start();
            SteamBridgeMod.LOG.info(
                "[UdpProxy] Relay started for conn={} steamID={} route={} voiceTarget={}",
                conn, steamID, status.describeRoute(), describeVoiceTarget()
            );
            return;
        }

        if (status.isTerminal()) {
            acceptedConns.remove(conn);
            UdpHostRelay relay = relayByConn.remove(conn);
            if (relay != null) {
                long in = relay.packetsFromSteam();
                long out = relay.packetsToSteam();
                relay.stop();
                SteamBridgeMod.LOG.info(
                    "[UdpProxy] Relay stopped conn={} steamID={} reason={} voicePkts steam->svc={} svc->steam={}",
                    conn, steamID, status.describeState(), in, out
                );
            }
        }
    }

    public void deliverFromSteamToServer(int conn, byte[] data) {
        UdpHostRelay relay = relayByConn.get(conn);
        if (relay == null) {
            SteamBridgeMod.LOG.warn(
                "[UdpProxy] Drop voice pkt from Steam: no relay for conn={} bytes={}",
                conn, data != null ? data.length : 0
            );
            return;
        }
        serverVoicePacketsIn.incrementAndGet();
        relay.forwardToLocalService(data);
    }

    /**
     * Called when a voice service socket binds an explicit non-zero port
     * (SVC changePort / dedicated config - not bind(0)).
     */
    public void registerBoundPort(int port) {
        if (port <= 1024 || port > 65535) {
            SteamBridgeMod.LOG.warn("[UdpProxy] Ignoring invalid voice bind port {}", port);
            return;
        }
        int prev = knownVoiceServerPort.getAndSet(port);
        if (prev != port) {
            SteamBridgeMod.LOG.info(
                "[UdpProxy] Voice server port registered: {} (was {}) resolved={}",
                port, prev == 0 ? "none" : prev, describeVoiceTarget()
            );
        }
    }

    /** Minecraft LAN/game port of the host; SVC rebinds voice here after publish. */
    public void setHostGamePort(int port) {
        if (port <= 0 || port > 65535) return;
        int prev = hostGamePort.getAndSet(port);
        if (prev != port) {
            SteamBridgeMod.LOG.info(
                "[UdpProxy] Host game port set: {} (was {}) resolved={}",
                port, prev == 0 ? "none" : prev, describeVoiceTarget()
            );
        }
    }

    public int getKnownVoiceServerPort() {
        return knownVoiceServerPort.get();
    }

    /**
     * Target for host-side voice relay:
     * detected explicit bind -> host game/LAN port -> SVC default 24454.
     */
    public int resolveVoiceTargetPort() {
        int known = knownVoiceServerPort.get();
        if (known > 0) return known;
        int game = hostGamePort.get();
        if (game > 0) return game;
        return DEFAULT_VOICE_PORT;
    }

    public String describeVoiceTarget() {
        int known = knownVoiceServerPort.get();
        int game = hostGamePort.get();
        int resolved = resolveVoiceTargetPort();
        String source;
        if (known > 0) source = "detected";
        else if (game > 0) source = "gamePort";
        else source = "default";
        return resolved + " (" + source + ", detected=" + known + ", gamePort=" + game + ")";
    }

    // -- Client-side API -------------------------------------------------------

    public void startClient(SteamID hostId) {
        if (udpClientConn != 0) return;
        clientVoicePacketsIn.set(0);
        clientVoicePacketsOut.set(0);
        loggedMissingClientImpl = false;
        loggedMissingClientConn = false;
        int udpVirtualPort = SteamBridgeConfig.virtualPort + 1;
        udpClientConn = SteamManager.getInstance().connectP2P(hostId, udpVirtualPort);
        if (udpClientConn == 0) {
            SteamBridgeMod.LOG.warn("[UdpProxy] Failed to start P2P connection.");
        } else {
            SteamBridgeMod.LOG.info("[UdpProxy] P2P connecting: conn={} virtualPort={}", udpClientConn, udpVirtualPort);
        }
    }

    public void stopClient() {
        int conn = udpClientConn;
        udpClientConn = 0;
        serverVoicePort = 0;
        activeClientImpl = null;
        if (conn != 0) SteamManager.getInstance().closeConnection(conn, SteamSocketsApi.APP_CLOSE_NORMAL, "UDP proxy disconnected");
        SteamBridgeMod.LOG.info(
            "[UdpProxy] Client stopped. voicePkts in={} out={}",
            clientVoicePacketsIn.get(), clientVoicePacketsOut.get()
        );
    }

    public boolean ownsClientConn(int conn) {
        return udpClientConn != 0 && conn == udpClientConn;
    }

    public boolean isClientActive() {
        return udpClientConn != 0;
    }

    public void onClientConnectionStatusChanged(int conn, long steamID, SteamConnectionStatus status, int oldState) {
        SteamBridgeMod.LOG.info(
            "[UdpProxy] Client conn={} state={} route={} oldState={}",
            conn, status.describeState(), status.describeRoute(),
            SteamSocketsApi.stateName(oldState)
        );

        if (status.isTerminal()) {
            activeClientImpl = null;
            udpClientConn = 0;
            SteamBridgeMod.LOG.info(
                "[UdpProxy] Client UDP closed. voicePkts in={} out={}",
                clientVoicePacketsIn.get(), clientVoicePacketsOut.get()
            );
        }
    }

    public void deliverFromSteamToClient(byte[] data) {
        SteamAwareDatagramImpl impl = activeClientImpl;
        if (impl == null) {
            if (!loggedMissingClientImpl) {
                loggedMissingClientImpl = true;
                SteamBridgeMod.LOG.warn(
                    "[UdpProxy] Drop voice pkt from host: client socket not intercepted yet (bytes={})",
                    data != null ? data.length : 0
                );
            }
            return;
        }
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = serverVoicePort > 0 ? serverVoicePort : DEFAULT_VOICE_PORT;
            clientVoicePacketsIn.incrementAndGet();
            impl.enqueueFromSteam(data, loopback, port);
        } catch (Exception e) {
            SteamBridgeMod.LOG.warn("[UdpProxy] deliverFromSteamToClient error: {}", e.getMessage());
        }
    }

    public void sendFromClientImpl(byte[] data) {
        int conn = udpClientConn;
        if (conn == 0) {
            if (!loggedMissingClientConn) {
                loggedMissingClientConn = true;
                SteamBridgeMod.LOG.warn(
                    "[UdpProxy] Drop voice pkt to host: UDP P2P conn not ready (bytes={})",
                    data != null ? data.length : 0
                );
            }
            return;
        }
        clientVoicePacketsOut.incrementAndGet();
        SteamManager.getInstance().sendVoiceBytes(conn, data);
    }

    void noteServerVoiceOut() {
        serverVoicePacketsOut.incrementAndGet();
    }

    // -- Package-internal (SteamAwareDatagramImpl) -----------------------------

    void setServerVoicePort(int port) {
        serverVoicePort = port;
        SteamBridgeMod.LOG.info("[UdpProxy] Client detected server voice port: {}", port);
    }

    void setActiveClientImpl(SteamAwareDatagramImpl impl) {
        activeClientImpl = impl;
        loggedMissingClientImpl = false;
    }

    SteamAwareDatagramImpl getActiveClientImpl() {
        return activeClientImpl;
    }

    void clearActiveClientImpl() {
        activeClientImpl = null;
    }
}
