/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.proxy;

import steambridge.SteamBridgeMod;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

final class SteamAwareDatagramImpl extends DatagramSocketImpl {

    private enum Mode { PENDING, CHANNEL, STEAM }

    private volatile Mode mode = Mode.PENDING;
    private volatile DatagramChannel channel;

    // STEAM mode: incoming packets from the host via Steam
    private final BlockingQueue<DatagramPacket> inbox = new LinkedBlockingQueue<>(512);

    // Buffered options (may be set before channel is created)
    private volatile int soTimeout = 0;
    private Boolean soReuseAddr = null;
    private Integer soRcvBuf = null;
    private Integer soSndbuf = null;
    private Boolean soBroadcast = null;

    private static final int RECV_BUF_SIZE = 65536;

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Lifecycle

    @Override
    protected void create() {
        // Channel is created lazily in bind() / send().
    }

    @Override
    protected void bind(int lport, InetAddress laddr) throws SocketException {
        if (mode == Mode.STEAM) return; // virtual socket - no real bind needed
        try {
            ensureChannel();
            applyBufferedOptions();
            InetSocketAddress addr = laddr != null
                ? new InetSocketAddress(laddr, lport)
                : new InetSocketAddress(lport);
            channel.bind(addr);
            InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
            localPort = bound.getPort();

            // Only explicit binds (lport != 0). SVC changePort/dedicated config; not bind(0).
            if (lport != 0 && localPort > 1024) {
                SteamUdpProxy.getInstance().registerBoundPort(localPort);
            }
        } catch (AlreadyBoundException ignored) {
            // already bound - nothing to do
        } catch (IOException e) {
            throw new SocketException(e.getMessage());
        }
    }

    @Override
    protected void close() {
        Mode prev = mode;
        mode = Mode.PENDING;
        if (prev == Mode.STEAM) {
            SteamUdpProxy proxy = SteamUdpProxy.getInstance();
            if (proxy.getActiveClientImpl() == this) proxy.clearActiveClientImpl();
        }
        DatagramChannel ch = channel;
        channel = null;
        if (ch != null) {
            try { ch.close(); } catch (IOException ignored) {}
        }
    }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Send

    @Override
    protected void send(DatagramPacket p) throws IOException {
        if (mode == Mode.PENDING) decideModeOnSend(p);

        if (mode == Mode.STEAM) {
            byte[] data = extractData(p);
            SteamUdpProxy.getInstance().sendFromClientImpl(data);
            return;
        }

        // CHANNEL mode
        ensureChannel();
        ByteBuffer buf = ByteBuffer.wrap(p.getData(), p.getOffset(), p.getLength());
        channel.send(buf, new InetSocketAddress(p.getAddress(), p.getPort()));
    }

    private void decideModeOnSend(DatagramPacket p) throws SocketException {
        InetAddress dest = p.getAddress();
        if (dest != null && dest.isLoopbackAddress()) {
            SteamUdpProxy proxy = SteamUdpProxy.getInstance();
            if (proxy.isClientActive()) {
                // Route this socket through Steam instead of real UDP.
                proxy.setServerVoicePort(p.getPort());
                proxy.setActiveClientImpl(this);
                // Keep localPort from earlier bind() if available; otherwise fake one.
                if (localPort == 0) localPort = ThreadLocalRandom.current().nextInt(49152, 65535);
                // Release channel if bind() already created one.
                DatagramChannel ch = channel;
                channel = null;
                if (ch != null) { try { ch.close(); } catch (IOException ignored) {} }
                mode = Mode.STEAM;
                SteamBridgeMod.LOG.info(
                    "[UdpProxy] Socket intercepted тЖТ steam mode (dest={}:{})",
                    dest.getHostAddress(), p.getPort());
                return;
            }
        }
        // Real UDP path - log why interception was skipped (helps diagnose voice issues).
        SteamUdpProxy proxy = SteamUdpProxy.getInstance();
        boolean loopback = dest != null && dest.isLoopbackAddress();
        if (loopback && !proxy.isClientActive()) {
            SteamBridgeMod.LOG.warn(
                "[UdpProxy] Socket NOT intercepted - loopback dest={}:{} but UDP P2P not active yet",
                dest.getHostAddress(), p.getPort());
        }
        try {
            ensureChannel();
            if (localPort == 0) {
                channel.bind(new InetSocketAddress(0));
                localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
            }
        } catch (IOException e) {
            throw new SocketException(e.getMessage());
        }
        mode = Mode.CHANNEL;
    }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Receive

    @Override
    protected void receive(DatagramPacket p) throws IOException {
        if (mode == Mode.STEAM) {
            receiveSteam(p);
            return;
        }
        // PENDING or CHANNEL: use real UDP. Do NOT set mode = CHANNEL here -
        // send() is the only place that decides mode. If we set CHANNEL here and
        // a background receive thread runs before the first send(), then send()
        // would never call decideModeOnSend() and the STEAM interception is skipped.
        ensureChannel();
        if (localPort == 0) {
            channel.bind(new InetSocketAddress(0));
            localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }
        try {
            receiveChannel(p);
        } catch (IOException e) {
            // Channel may have been closed by send() switching to STEAM mode.
            if (mode == Mode.STEAM) {
                receiveSteam(p);
                return;
            }
            throw e;
        }
    }

    private void receiveSteam(DatagramPacket p) throws IOException {
        try {
            DatagramPacket incoming;
            if (soTimeout > 0) {
                incoming = inbox.poll(soTimeout, TimeUnit.MILLISECONDS);
                if (incoming == null) throw new SocketTimeoutException("Receive timed out");
            } else {
                incoming = inbox.take();
            }
            int available = p.getData().length - p.getOffset();
            int len = Math.min(incoming.getLength(), available);
            System.arraycopy(incoming.getData(), incoming.getOffset(), p.getData(), p.getOffset(), len);
            p.setLength(len);
            p.setAddress(incoming.getAddress());
            p.setPort(incoming.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocketException("Interrupted while waiting for voice packet");
        }
    }

    private void receiveChannel(DatagramPacket p) throws IOException {
        DatagramChannel ch = channel;
        if (ch == null || !ch.isOpen()) throw new SocketException("Socket closed");

        if (soTimeout > 0) {
            // Use a Selector for timed receive without blocking indefinitely.
            try (Selector sel = Selector.open()) {
                ch.configureBlocking(false);
                ch.register(sel, SelectionKey.OP_READ);
                int ready = sel.select(soTimeout);
                ch.configureBlocking(true);
                if (ready == 0) throw new SocketTimeoutException("Receive timed out");
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(RECV_BUF_SIZE);
        InetSocketAddress src = (InetSocketAddress) ch.receive(buf);
        if (src != null) {
            buf.flip();
            int available = p.getData().length - p.getOffset();
            int len = Math.min(buf.remaining(), available);
            buf.get(p.getData(), p.getOffset(), len);
            p.setLength(len);
            p.setAddress(src.getAddress());
            p.setPort(src.getPort());
        }
    }

    /**
     * Called by {@link SteamUdpProxy} when a packet from the host arrives via Steam.
     * The source address will appear to SVC as if the packet came from the voice server.
     */
    void enqueueFromSteam(byte[] data, InetAddress srcAddr, int srcPort) {
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        DatagramPacket p = new DatagramPacket(copy, copy.length, srcAddr, srcPort);
        if (!inbox.offer(p)) {
            SteamBridgeMod.LOG.warn("[UdpProxy] Client inbox full, dropping incoming voice packet.");
        }
    }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Options

    @Override
    public void setOption(int optID, Object value) throws SocketException {
        switch (optID) {
            case SocketOptions.SO_TIMEOUT:
                soTimeout = toInt(value);
                break;
            case SocketOptions.SO_REUSEADDR:
                soReuseAddr = toBool(value);
                break;
            case SocketOptions.SO_RCVBUF:
                soRcvBuf = toInt(value);
                break;
            case SocketOptions.SO_SNDBUF:
                soSndbuf = toInt(value);
                break;
            case SocketOptions.SO_BROADCAST:
                soBroadcast = toBool(value);
                break;
            default:
                break;
        }
        DatagramChannel ch = channel;
        if (ch != null && ch.isOpen()) {
            try { applyOption(ch, optID, value); } catch (IOException e) { throw new SocketException(e.getMessage()); }
        }
    }

    @Override
    public Object getOption(int optID) throws SocketException {
        if (optID == SocketOptions.SO_TIMEOUT) return soTimeout;
        DatagramChannel ch = channel;
        if (ch == null || !ch.isOpen()) return null;
        try {
            switch (optID) {
                case SocketOptions.SO_RCVBUF:
                    return ch.getOption(StandardSocketOptions.SO_RCVBUF);
                case SocketOptions.SO_SNDBUF:
                    return ch.getOption(StandardSocketOptions.SO_SNDBUF);
                case SocketOptions.SO_REUSEADDR:
                    return ch.getOption(StandardSocketOptions.SO_REUSEADDR);
                case SocketOptions.SO_BROADCAST:
                    return ch.getOption(StandardSocketOptions.SO_BROADCAST);
                default:
                    return null;
            }
        } catch (IOException e) {
            throw new SocketException(e.getMessage());
        }
    }

    private static void applyOption(DatagramChannel ch, int optID, Object value) throws IOException {
        switch (optID) {
            case SocketOptions.SO_RCVBUF:
                ch.setOption(StandardSocketOptions.SO_RCVBUF, (Integer) value);
                break;
            case SocketOptions.SO_SNDBUF:
                ch.setOption(StandardSocketOptions.SO_SNDBUF, (Integer) value);
                break;
            case SocketOptions.SO_REUSEADDR:
                ch.setOption(StandardSocketOptions.SO_REUSEADDR, (Boolean) value);
                break;
            case SocketOptions.SO_BROADCAST:
                ch.setOption(StandardSocketOptions.SO_BROADCAST, (Boolean) value);
                break;
            default:
                break;
        }
    }

    private void applyBufferedOptions() throws IOException {
        DatagramChannel ch = channel;
        if (ch == null) return;
        if (soReuseAddr != null) ch.setOption(StandardSocketOptions.SO_REUSEADDR, soReuseAddr);
        if (soRcvBuf    != null) ch.setOption(StandardSocketOptions.SO_RCVBUF,    soRcvBuf);
        if (soSndbuf    != null) ch.setOption(StandardSocketOptions.SO_SNDBUF,    soSndbuf);
        if (soBroadcast != null) ch.setOption(StandardSocketOptions.SO_BROADCAST, soBroadcast);
    }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Helpers

    private synchronized void ensureChannel() throws SocketException {
        if (channel == null || !channel.isOpen()) {
            try {
                channel = DatagramChannel.open();
                channel.configureBlocking(true);
            } catch (IOException e) {
                throw new SocketException("Failed to open DatagramChannel: " + e.getMessage());
            }
        }
    }

    private static byte[] extractData(DatagramPacket p) {
        if (p.getOffset() == 0 && p.getLength() == p.getData().length) return p.getData();
        byte[] copy = new byte[p.getLength()];
        System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
        return copy;
    }

    private static int toInt(Object v) {
        return v instanceof Integer ? ((Integer) v).intValue() : 0;
    }

    private static boolean toBool(Object v) {
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Multicast stubs (voice mods don't use multicast)

    @Override protected void join(InetAddress g) throws IOException { throw new UnsupportedOperationException("Multicast not supported"); }
    @Override protected void leave(InetAddress g) throws IOException { throw new UnsupportedOperationException("Multicast not supported"); }
    @Override protected void joinGroup(SocketAddress m, NetworkInterface i) throws IOException { throw new UnsupportedOperationException("Multicast not supported"); }
    @Override protected void leaveGroup(SocketAddress m, NetworkInterface i) throws IOException { throw new UnsupportedOperationException("Multicast not supported"); }

    // тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА Deprecated but still abstract

    @Override protected int  peek(InetAddress i) { return 0; }
    @Override protected int  peekData(DatagramPacket p) { return 0; }
    @Override protected void setTTL(byte ttl) {}
    @Override protected byte getTTL() { return 0; }
    @Override protected void setTimeToLive(int ttl) throws IOException {}
    @Override protected int  getTimeToLive() throws IOException { return 0; }
}
