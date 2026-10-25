/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import com.codedisaster.steamworks.SteamID;

import steambridge.SteamBridgeConfig;
import steambridge.steam.SteamConnectionStatus;
import steambridge.steam.SteamSocketsApi;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** UDP/voice tunnel on Steam virtual port (MC port + 1). */
public final class SteamUdpProxy implements steambridge.core.VoiceTransport {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("steambridge");
    private volatile steambridge.core.GameAttemptOwner gameOwner;
    private volatile steambridge.voice.VoiceNativeDriver driver;

    public void install(steambridge.voice.VoiceNativeDriver driver) {
        this.driver = driver;
        steambridge.voice.VoiceIntegration.install(this);
    }

    private static final SteamUdpProxy INSTANCE = new SteamUdpProxy();
    static final int DEFAULT_VOICE_PORT = 24454;

    public static SteamUdpProxy getInstance() {
        return INSTANCE;
    }

    // Server
    private volatile int udpListenSocket = 0;
    private final Map<Integer, UdpHostRelay> relayByConn = new ConcurrentHashMap<>();
    private final Object serverLock = new Object();
    private final Map<Integer, VoiceSession> voiceSessions = new HashMap<>();
    private final Map<Long, VoiceSession> voiceByPeer = new HashMap<>();

    private static final class VoiceSession {
        final int handle;
        final long steamId;
        final Object gameSession;
        final steambridge.voice.VoiceNativeDriver driver;
        boolean accepted;

        VoiceSession(
                int handle,
                long steamId,
                Object gameSession,
                steambridge.voice.VoiceNativeDriver driver) {
            this.handle = handle;
            this.steamId = steamId;
            this.gameSession = gameSession;
            this.driver = driver;
        }
    }

    private static final class VoiceBinding {
        final Object owner;
        final InetSocketAddress address;

        VoiceBinding(Object owner, InetSocketAddress address) {
            this.owner = owner;
            this.address = address;
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<VoiceBinding> voiceServerAddress =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicInteger hostGamePort = new AtomicInteger(0);

    private final AtomicLong serverVoicePacketsIn = new AtomicLong();
    private final AtomicLong serverVoicePacketsOut = new AtomicLong();
    private final AtomicLong clientVoicePacketsIn = new AtomicLong();
    private final AtomicLong clientVoicePacketsOut = new AtomicLong();

    private volatile int udpClientConn = 0;
    private final java.util.concurrent.atomic.AtomicReference<steambridge.core.VoiceClient>
            activeClientImpl =
                    new java.util.concurrent.atomic.AtomicReference<steambridge.core.VoiceClient>();
    private volatile int serverVoicePort = 0;
    private volatile boolean loggedMissingClientImpl;
    private volatile boolean loggedMissingClientConn;
    private boolean startingClient;

    private SteamUdpProxy() {}

    public void startServer() {
        int udpVirtualPort = SteamBridgeConfig.settings().virtualPort + 1;
        serverVoicePacketsIn.set(0);
        serverVoicePacketsOut.set(0);
        udpListenSocket = driver.createListenSocketP2P(udpVirtualPort);
        if (udpListenSocket == 0) {
            LOG.warn("[UdpProxy] Failed to open listen socket on virtual port {}.", udpVirtualPort);
        } else {
            LOG.info(
                    "[UdpProxy] Listen socket open: handle={} virtualPort={}",
                    udpListenSocket,
                    udpVirtualPort);
        }
        LOG.info(
                "[UdpProxy] Voice target: detected={} gamePort={} resolved={}",
                boundVoiceAddress(),
                hostGamePort.get(),
                resolveVoiceTargetPort());
    }

    public void stopServer() {
        List<VoiceSession> closing;
        int ls;
        synchronized (serverLock) {
            ls = udpListenSocket;
            udpListenSocket = 0;
            closing = new ArrayList<>(voiceSessions.values());
            voiceSessions.clear();
            voiceByPeer.clear();
            for (UdpHostRelay relay : relayByConn.values()) relay.stop();
            relayByConn.clear();
            VoiceBinding binding = voiceServerAddress.get();
            if (binding != null && binding.owner == null)
                voiceServerAddress.compareAndSet(binding, null);
            hostGamePort.set(0);
        }
        for (VoiceSession session : closing) closeVoiceSession(session, "UDP proxy stopped");
        if (ls != 0) driver.closeListenSocket(ls);
        LOG.info(
                "[UdpProxy] Server stopped. voicePkts in={} out={}",
                serverVoicePacketsIn.get(),
                serverVoicePacketsOut.get());
    }

    public void closeServerPeer(long steamId, Object gameSession) {
        VoiceSession session;
        synchronized (serverLock) {
            session = voiceByPeer.get(steamId);
            if (session == null || session.gameSession != gameSession) return;
            removeVoiceSession(session);
        }
        closeVoiceSession(session, "Game session ended");
    }

    private void removeVoiceSession(VoiceSession session) {
        voiceSessions.remove(session.handle, session);
        voiceByPeer.remove(session.steamId, session);
        UdpHostRelay relay = relayByConn.remove(session.handle);
        if (relay != null) relay.stop();
    }

    private void closeVoiceSession(VoiceSession session, String reason) {
        session.driver.closeConnection(session.handle, SteamSocketsApi.APP_CLOSE_NORMAL, reason);
    }

    public boolean ownsListenSocket(int listenSocket) {
        return udpListenSocket != 0 && listenSocket == udpListenSocket;
    }

    public boolean ownsServerConn(int conn) {
        synchronized (serverLock) {
            return voiceSessions.containsKey(conn);
        }
    }

    public void onLocalConnectionClosed(int conn) {
        if (conn == 0) return;
        synchronized (serverLock) {
            VoiceSession session = voiceSessions.get(conn);
            if (session != null) removeVoiceSession(session);
        }
        steambridge.core.VoiceClient closed = null;
        synchronized (this) {
            if (udpClientConn == conn) {
                udpClientConn = 0;
                gameOwner = null;
                serverVoicePort = 0;
                closed = activeClientImpl.getAndSet(null);
            }
        }
        if (closed != null) closed.closeVoice();
    }

    public void onServerConnectionStatusChanged(
            int conn, long steamID, SteamConnectionStatus status, int oldState) {
        if (status.isTerminal()) {
            synchronized (serverLock) {
                VoiceSession session = voiceSessions.get(conn);
                if (session != null) removeVoiceSession(session);
            }
            return;
        }
        int state = status.getState();
        if (state != SteamSocketsApi.STATE_CONNECTING
                && state != SteamSocketsApi.STATE_FINDING_ROUTE
                && state != SteamSocketsApi.STATE_CONNECTED) return;
        steambridge.voice.VoiceNativeDriver nativeDriver = driver;
        Object gameSession = nativeDriver.getVoiceSessionToken(steamID);
        VoiceSession session;
        boolean accept = false;
        synchronized (serverLock) {
            session = voiceSessions.get(conn);
            if (udpListenSocket == 0
                    || gameSession == null
                    || (session != null
                            && (session.gameSession != gameSession
                                    || session.steamId != steamID
                                    || session.driver != nativeDriver))) {
                if (session != null) removeVoiceSession(session);
                session = null;
            } else if (session == null) {
                if (!voiceByPeer.containsKey(steamID)) {
                    session = new VoiceSession(conn, steamID, gameSession, nativeDriver);
                    voiceSessions.put(conn, session);
                    voiceByPeer.put(steamID, session);
                }
            }
            if (session != null && state != SteamSocketsApi.STATE_CONNECTED && !session.accepted) {
                session.accepted = true;
                accept = true;
            }
            if (session != null
                    && state == SteamSocketsApi.STATE_CONNECTED
                    && !relayByConn.containsKey(conn)) {
                UdpHostRelay relay = new UdpHostRelay(this, session.driver, conn, steamID);
                relayByConn.put(conn, relay);
                if (!relay.start()) {
                    removeVoiceSession(session);
                    session = null;
                }
            }
        }
        if (session == null) {
            nativeDriver.closeConnection(
                    conn,
                    SteamSocketsApi.APP_CLOSE_LOCAL_ERROR,
                    "Voice requires one active game session");
            return;
        }
        if (nativeDriver.getVoiceSessionToken(steamID) != gameSession) {
            closeServerPeer(steamID, gameSession);
            return;
        }
        if (accept && !nativeDriver.acceptConnection(conn)) {
            synchronized (serverLock) {
                if (voiceSessions.get(conn) == session) removeVoiceSession(session);
            }
            closeVoiceSession(session, "UDP proxy accept failed");
        }
    }

    public void deliverFromSteamToServer(int conn, byte[] data) {
        UdpHostRelay relay = relayByConn.get(conn);
        if (relay == null) {
            LOG.warn(
                    "[UdpProxy] Drop voice pkt from Steam: no relay for conn={} bytes={}",
                    conn,
                    data != null ? data.length : 0);
            return;
        }
        serverVoicePacketsIn.incrementAndGet();
        relay.forwardToLocalService(data);
    }

    public void registerBoundPort(int port) {
        if (port <= 0 || port > 65535) return;
        registerBoundAddress(new InetSocketAddress("127.0.0.1", port));
    }

    @Override
    public void registerBoundAddress(InetSocketAddress address) {
        registerBoundAddress(null, address);
    }

    @Override
    public void registerBoundAddress(Object owner, InetSocketAddress address) {
        if (address == null || address.isUnresolved() || address.getPort() == 0) {
            LOG.warn("[UdpProxy] Ignoring invalid voice bind address {}", address);
            return;
        }
        InetAddress target = address.getAddress();
        if (target.isAnyLocalAddress()) {
            try {
                target =
                        InetAddress.getByName(
                                target instanceof java.net.Inet6Address ? "::1" : "127.0.0.1");
            } catch (java.net.UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        InetSocketAddress endpoint = new InetSocketAddress(target, address.getPort());
        VoiceBinding previous;
        do {
            previous = voiceServerAddress.get();
            if (owner == null && previous != null && previous.owner != null) return;
        } while (!voiceServerAddress.compareAndSet(previous, new VoiceBinding(owner, endpoint)));
        InetSocketAddress oldAddress = previous == null ? null : previous.address;
        if (!endpoint.equals(oldAddress))
            LOG.info(
                    "[UdpProxy] Voice server address registered: {} (was {})",
                    endpoint,
                    oldAddress);
    }

    @Override
    public void unregisterBoundPort(int port) {
        unregisterBoundAddress(null, port);
    }

    @Override
    public void unregisterBoundAddress(Object owner, int port) {
        VoiceBinding previous = voiceServerAddress.get();
        if (previous != null && previous.owner == owner && previous.address.getPort() == port)
            voiceServerAddress.compareAndSet(previous, null);
    }

    private InetSocketAddress boundVoiceAddress() {
        VoiceBinding binding = voiceServerAddress.get();
        return binding == null ? null : binding.address;
    }

    public void setHostGamePort(int port) {
        if (port <= 0 || port > 65535) return;
        int prev = hostGamePort.getAndSet(port);
        if (prev != port) {
            LOG.info(
                    "[UdpProxy] Host game port set: {} (was {}) resolved={}",
                    port,
                    prev == 0 ? "none" : prev,
                    describeVoiceTarget());
        }
    }

    /** detected bind, else game port, else 24454. */
    public int resolveVoiceTargetPort() {
        return resolveVoiceTarget().getPort();
    }

    public InetSocketAddress resolveVoiceTarget() {
        InetSocketAddress known = boundVoiceAddress();
        if (known != null) return known;
        int game = hostGamePort.get();
        return new InetSocketAddress("127.0.0.1", game > 0 ? game : DEFAULT_VOICE_PORT);
    }

    public String describeVoiceTarget() {
        InetSocketAddress known = boundVoiceAddress();
        int game = hostGamePort.get();
        InetSocketAddress resolved = resolveVoiceTarget();
        String source;
        if (known != null) source = "detected";
        else if (game > 0) source = "gamePort";
        else source = "default";
        return resolved + " (" + source + ", detected=" + known + ", gamePort=" + game + ")";
    }

    public synchronized void startClient(SteamID hostId, steambridge.core.GameAttemptOwner owner) {
        if (driver.getActiveClient() != owner || !owner.isAlive()) return;
        if (udpClientConn != 0) return;
        gameOwner = owner;
        clientVoicePacketsIn.set(0);
        clientVoicePacketsOut.set(0);
        loggedMissingClientImpl = false;
        loggedMissingClientConn = false;
        int udpVirtualPort = SteamBridgeConfig.settings().virtualPort + 1;
        startingClient = true;
        try {
            udpClientConn =
                    driver.connectP2P(
                            hostId,
                            udpVirtualPort,
                            this,
                            steambridge.core.ConnectionRegistry.Kind.UDP);
        } finally {
            startingClient = false;
        }
        if (gameOwner != owner
                || !owner.isAlive()
                || !driver.ownsConnectionOwner(udpClientConn, this)) {
            int abandoned = udpClientConn;
            udpClientConn = 0;
            if (abandoned != 0)
                driver.closeConnection(
                        abandoned, SteamSocketsApi.APP_CLOSE_NORMAL, "Cancelled voice session");
            return;
        }
        if (udpClientConn == 0) {
            LOG.warn("[UdpProxy] Failed to start P2P connection.");
        } else {
            LOG.info(
                    "[UdpProxy] P2P connecting: conn={} virtualPort={}",
                    udpClientConn,
                    udpVirtualPort);
        }
    }

    public void stopClient() {
        stopClient(null);
    }

    public boolean ownsClientConn(int conn) {
        return udpClientConn != 0 && conn == udpClientConn;
    }

    public boolean isClientActive() {
        return udpClientConn != 0;
    }

    public void onClientConnectionStatusChanged(
            int conn, long steamID, SteamConnectionStatus status, int oldState) {
        steambridge.core.VoiceClient closedSocket = null;
        synchronized (this) {
            if (udpClientConn == 0
                    && startingClient
                    && gameOwner != null
                    && gameOwner.isAlive()
                    && driver.ownsConnectionOwner(conn, this)) udpClientConn = conn;
            if (udpClientConn != conn) return;
            LOG.info(
                    "[UdpProxy] Client conn={} state={} route={} oldState={}",
                    conn,
                    status.describeState(),
                    status.describeRoute(),
                    SteamSocketsApi.stateName(oldState));
            if (status.isTerminal()) {
                udpClientConn = 0;
                gameOwner = null;
                serverVoicePort = 0;
                closedSocket = activeClientImpl.getAndSet(null);
            }
        }
        if (closedSocket != null) closedSocket.closeVoice();
    }

    public void deliverFromSteamToClient(byte[] data) {
        steambridge.core.VoiceClient impl = activeClientImpl.get();
        if (impl == null) {
            if (!loggedMissingClientImpl) {
                loggedMissingClientImpl = true;
                LOG.warn(
                        "[UdpProxy] Drop voice pkt from host: client socket not intercepted yet"
                                + " (bytes={})",
                        data != null ? data.length : 0);
            }
            return;
        }
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = serverVoicePort > 0 ? serverVoicePort : DEFAULT_VOICE_PORT;
            clientVoicePacketsIn.incrementAndGet();
            impl.receiveVoice(data, loopback, port);
        } catch (Exception e) {
            LOG.warn("[UdpProxy] deliverFromSteamToClient error: {}", e.getMessage());
        }
    }

    public synchronized void sendFromClient(steambridge.core.VoiceClient owner, byte[] data) {
        if (owner == null || activeClientImpl.get() != owner) return;
        sendFromClientImpl(data);
    }

    public synchronized void sendFromClientImpl(byte[] data) {
        int conn = udpClientConn;
        if (conn == 0) {
            if (!loggedMissingClientConn) {
                loggedMissingClientConn = true;
                LOG.warn(
                        "[UdpProxy] Drop voice pkt to host: UDP P2P conn not ready (bytes={})",
                        data != null ? data.length : 0);
            }
            return;
        }
        clientVoicePacketsOut.incrementAndGet();
        driver.sendVoiceBytes(conn, data);
    }

    void noteServerVoiceOut() {
        serverVoicePacketsOut.incrementAndGet();
    }

    void setServerVoicePort(int port) {
        serverVoicePort = port;
        LOG.info("[UdpProxy] Client detected server voice port: {}", port);
    }

    void setActiveClientImpl(steambridge.core.VoiceClient impl) {
        activeClientImpl.set(impl);
        loggedMissingClientImpl = false;
    }

    steambridge.core.VoiceClient getActiveClientImpl() {
        return activeClientImpl.get();
    }

    void clearActiveClientImpl(steambridge.core.VoiceClient impl) {
        activeClientImpl.compareAndSet(impl, null);
    }

    public void stopClient(steambridge.core.GameAttemptOwner owner) {
        int conn;
        steambridge.core.VoiceClient socket;
        synchronized (this) {
            if (owner != null && gameOwner != owner) return;
            gameOwner = null;
            conn = udpClientConn;
            udpClientConn = 0;
            serverVoicePort = 0;
            socket = activeClientImpl.getAndSet(null);
        }
        if (socket != null) socket.closeVoice();
        if (conn != 0)
            driver.closeConnection(
                    conn, SteamSocketsApi.APP_CLOSE_NORMAL, "UDP proxy disconnected");
        LOG.info(
                "[UdpProxy] Client stopped. voicePkts in={} out={}",
                clientVoicePacketsIn.get(),
                clientVoicePacketsOut.get());
    }

    @Override
    public steambridge.core.VoiceClient voiceClient() {
        return activeClientImpl.get();
    }

    @Override
    public synchronized boolean claimVoiceClient(steambridge.core.VoiceClient client, int port) {
        if (!isClientActive() || !activeClientImpl.compareAndSet(null, client)) return false;
        setServerVoicePort(port);
        loggedMissingClientImpl = false;
        return true;
    }

    @Override
    public void releaseVoiceClient(steambridge.core.VoiceClient client) {
        activeClientImpl.compareAndSet(client, null);
    }
}
