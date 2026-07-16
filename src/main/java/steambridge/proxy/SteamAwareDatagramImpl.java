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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom {@link DatagramSocketImpl} that can divert SVC client UDP over Steam.
 *
 * <p>Java 8 with a SecurityManager calls {@link #peekData} then
 * {@code peekPacket.getAddress().getHostAddress()}. Peek must fill address.
 * NIO has no MSG_PEEK, so one packet is held in {@link #heldPacket}.
 *
 * <p>Never block on network I/O under a lock that {@link #close()} also needs.
 * Close the channel first so blocked receivers unblock (SVC port change on
 * Open-to-Steam closes sockets on the client thread while a voice thread may
 * still be in peek/receive).
 */
final class SteamAwareDatagramImpl extends DatagramSocketImpl {

    private enum Mode { PENDING, CHANNEL, STEAM }

    private volatile Mode mode = Mode.PENDING;
    private volatile DatagramChannel channel;
    private volatile boolean closed;

    private final BlockingQueue<DatagramPacket> inbox = new LinkedBlockingQueue<>(512);

    /** Emulated MSG_PEEK; never guard with a lock held across blocking I/O. */
    private final AtomicReference<DatagramPacket> heldPacket = new AtomicReference<>();

    private volatile int soTimeout = 0;
    private Boolean soReuseAddr;
    private Integer soRcvBuf;
    private Integer soSndbuf;
    private Boolean soBroadcast;
    private InetAddress boundAddress;

    private static final int RECV_BUF_SIZE = 65536;
    /** Wakes STEAM {@link BlockingQueue#take()} after {@link #close()}. */
    private static final DatagramPacket POISON =
        new DatagramPacket(new byte[0], 0, InetAddress.getLoopbackAddress(), 0);

    @Override
    protected void create() {
        // Channel opened lazily in bind() / send().
    }

    @Override
    protected void bind(int lport, InetAddress laddr) throws SocketException {
        if (mode == Mode.STEAM) return;
        try {
            ensureChannel();
            applyBufferedOptions();
            InetSocketAddress addr = laddr != null
                ? new InetSocketAddress(laddr, lport)
                : new InetSocketAddress(lport);
            channel.bind(addr);
            InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
            localPort = bound.getPort();
            boundAddress = bound.getAddress();

            if (lport != 0 && localPort > 1024) {
                SteamUdpProxy.getInstance().registerBoundPort(localPort);
            }
        } catch (AlreadyBoundException ignored) {
            // ok
        } catch (IOException e) {
            throw new SocketException(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @Override
    protected void close() {
        closed = true;
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

        heldPacket.set(null);
        inbox.clear();
        inbox.offer(POISON);
    }

    @Override
    protected void send(DatagramPacket p) throws IOException {
        if (mode == Mode.PENDING) decideModeOnSend(p);

        if (mode == Mode.STEAM) {
            SteamUdpProxy.getInstance().sendFromClientImpl(extractData(p));
            return;
        }

        ensureChannel();
        ByteBuffer buf = ByteBuffer.wrap(p.getData(), p.getOffset(), p.getLength());
        channel.send(buf, new InetSocketAddress(p.getAddress(), p.getPort()));
    }

    private void decideModeOnSend(DatagramPacket p) throws SocketException {
        InetAddress dest = p.getAddress();
        if (dest != null && dest.isLoopbackAddress()) {
            SteamUdpProxy proxy = SteamUdpProxy.getInstance();
            if (proxy.isClientActive()) {
                proxy.setServerVoicePort(p.getPort());
                proxy.setActiveClientImpl(this);
                if (localPort == 0) localPort = ThreadLocalRandom.current().nextInt(49152, 65535);
                DatagramChannel ch = channel;
                channel = null;
                if (ch != null) {
                    try { ch.close(); } catch (IOException ignored) {}
                }
                mode = Mode.STEAM;
                SteamBridgeMod.LOG.info(
                    "[UdpProxy] Socket intercepted -> steam mode (dest={}:{})",
                    dest.getHostAddress(), p.getPort());
                return;
            }
        }
        try {
            ensureChannel();
            if (localPort == 0) {
                channel.bind(new InetSocketAddress(0));
                InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
                localPort = bound.getPort();
                boundAddress = bound.getAddress();
            }
        } catch (IOException e) {
            throw new SocketException(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        mode = Mode.CHANNEL;
    }

    @Override
    protected void receive(DatagramPacket p) throws IOException {
        DatagramPacket held = heldPacket.getAndSet(null);
        if (held != null) {
            copyPacket(held, p);
            return;
        }
        receiveInto(p);
    }

    /**
     * Emulate MSG_PEEK (NIO has none). Java 8 + SecurityManager requires a non-null
     * {@link DatagramPacket#getAddress()} after peek.
     */
    @Override
    protected int peekData(DatagramPacket p) throws IOException {
        DatagramPacket held = heldPacket.get();
        if (held == null) {
            DatagramPacket tmp = new DatagramPacket(new byte[RECV_BUF_SIZE], RECV_BUF_SIZE);
            receiveInto(tmp);
            heldPacket.compareAndSet(null, tmp);
            held = heldPacket.get();
            if (held == null) throw new SocketException("Socket closed");
        }
        copyPacket(held, p);
        return p.getPort();
    }

    @Override
    protected int peek(InetAddress ignored) throws IOException {
        DatagramPacket tmp = new DatagramPacket(new byte[RECV_BUF_SIZE], RECV_BUF_SIZE);
        return peekData(tmp);
    }

    private void receiveInto(DatagramPacket p) throws IOException {
        if (closed) throw new SocketException("Socket closed");
        if (mode == Mode.STEAM) {
            receiveSteam(p);
            return;
        }
        ensureChannel();
        if (localPort == 0) {
            channel.bind(new InetSocketAddress(0));
            InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
            localPort = bound.getPort();
            boundAddress = bound.getAddress();
        }
        try {
            receiveChannel(p);
        } catch (IOException e) {
            if (mode == Mode.STEAM && !closed) {
                receiveSteam(p);
                return;
            }
            throw e;
        }
    }

    private void receiveSteam(DatagramPacket p) throws IOException {
        if (closed) throw new SocketException("Socket closed");
        try {
            DatagramPacket incoming;
            if (soTimeout > 0) {
                incoming = inbox.poll(soTimeout, TimeUnit.MILLISECONDS);
                if (incoming == null) throw new SocketTimeoutException("Receive timed out");
            } else {
                incoming = inbox.take();
            }
            if (closed || incoming == POISON) {
                throw new SocketException("Socket closed");
            }
            copyPacket(incoming, p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocketException("Interrupted while waiting for voice packet");
        }
    }

    private void receiveChannel(DatagramPacket p) throws IOException {
        if (p == null || p.getData() == null) throw new SocketException("Null datagram packet");

        long deadline = soTimeout > 0 ? System.currentTimeMillis() + soTimeout : 0L;
        ByteBuffer buf = ByteBuffer.allocate(RECV_BUF_SIZE);

        while (true) {
            if (mode == Mode.STEAM) {
                throw new SocketException("Switched to STEAM mode");
            }
            DatagramChannel ch = channel;
            if (ch == null || !ch.isOpen()) {
                throw new SocketException("Socket closed");
            }

            if (soTimeout > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new SocketTimeoutException("Receive timed out");
                try (Selector sel = Selector.open()) {
                    ch.configureBlocking(false);
                    try {
                        ch.register(sel, SelectionKey.OP_READ);
                        int ready = sel.select(remaining);
                        if (ready == 0) throw new SocketTimeoutException("Receive timed out");
                    } finally {
                        try {
                            if (ch.isOpen()) ch.configureBlocking(true);
                        } catch (IOException ignored) {}
                    }
                } catch (ClosedChannelException e) {
                    throw new SocketException("Socket closed");
                }
            } else {
                try {
                    if (ch.isOpen() && !ch.isBlocking()) ch.configureBlocking(true);
                } catch (IOException ignored) {}
            }

            buf.clear();
            SocketAddress raw;
            try {
                raw = ch.receive(buf);
            } catch (ClosedChannelException e) {
                throw new SocketException("Socket closed");
            }
            if (raw == null) {
                if (soTimeout > 0 && System.currentTimeMillis() >= deadline) {
                    throw new SocketTimeoutException("Receive timed out");
                }
                if (channel == null || !ch.isOpen()) {
                    throw new SocketException("Socket closed");
                }
                continue;
            }
            if (!(raw instanceof InetSocketAddress)) {
                throw new SocketException("Unexpected datagram source: " + raw);
            }
            InetSocketAddress src = (InetSocketAddress) raw;
            buf.flip();
            int available = p.getData().length - p.getOffset();
            if (available < 0) available = 0;
            int len = Math.min(buf.remaining(), available);
            if (len > 0) {
                buf.get(p.getData(), p.getOffset(), len);
            }
            p.setLength(len);
            p.setAddress(src.getAddress() != null ? src.getAddress() : InetAddress.getLoopbackAddress());
            p.setPort(src.getPort());
            return;
        }
    }

    private static void copyPacket(DatagramPacket src, DatagramPacket dst) {
        int available = dst.getData().length - dst.getOffset();
        if (available < 0) available = 0;
        int len = Math.min(src.getLength(), available);
        if (len > 0) {
            System.arraycopy(src.getData(), src.getOffset(), dst.getData(), dst.getOffset(), len);
        }
        dst.setLength(len);
        dst.setAddress(src.getAddress() != null ? src.getAddress() : InetAddress.getLoopbackAddress());
        dst.setPort(src.getPort());
    }

    void enqueueFromSteam(byte[] data, InetAddress srcAddr, int srcPort) {
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        InetAddress addr = srcAddr != null ? srcAddr : InetAddress.getLoopbackAddress();
        DatagramPacket p = new DatagramPacket(copy, copy.length, addr, srcPort);
        if (!inbox.offer(p)) {
            SteamBridgeMod.LOG.warn("[UdpProxy] Client inbox full, dropping incoming voice packet.");
        }
    }

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
            try {
                applyOption(ch, optID, value);
            } catch (IOException e) {
                throw new SocketException(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
    }

    @Override
    public Object getOption(int optID) throws SocketException {
        if (optID == SocketOptions.SO_TIMEOUT) return soTimeout;
        if (optID == SocketOptions.SO_BINDADDR) {
            if (boundAddress != null) return boundAddress;
            try {
                DatagramChannel ch = channel;
                if (ch != null && ch.isOpen()) {
                    SocketAddress local = ch.getLocalAddress();
                    if (local instanceof InetSocketAddress) {
                        return ((InetSocketAddress) local).getAddress();
                    }
                }
            } catch (IOException ignored) {}
            try {
                return InetAddress.getByName("0.0.0.0");
            } catch (UnknownHostException e) {
                return InetAddress.getLoopbackAddress();
            }
        }
        DatagramChannel ch = channel;
        if (ch == null || !ch.isOpen()) {
            if (optID == SocketOptions.SO_REUSEADDR) return soReuseAddr != null && soReuseAddr;
            if (optID == SocketOptions.SO_BROADCAST) return soBroadcast == null || soBroadcast;
            if (optID == SocketOptions.SO_RCVBUF) return soRcvBuf != null ? soRcvBuf : 65536;
            if (optID == SocketOptions.SO_SNDBUF) return soSndbuf != null ? soSndbuf : 65536;
            return null;
        }
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
            throw new SocketException(e.getMessage() != null ? e.getMessage() : e.toString());
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
        if (soRcvBuf != null) ch.setOption(StandardSocketOptions.SO_RCVBUF, soRcvBuf);
        if (soSndbuf != null) ch.setOption(StandardSocketOptions.SO_SNDBUF, soSndbuf);
        if (soBroadcast != null) ch.setOption(StandardSocketOptions.SO_BROADCAST, soBroadcast);
    }

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

    // MulticastSocket also uses this factory (LAN scan). No-ops avoid hard failures.
    @Override protected void join(InetAddress g) {}
    @Override protected void leave(InetAddress g) {}
    @Override protected void joinGroup(SocketAddress m, NetworkInterface i) {}
    @Override protected void leaveGroup(SocketAddress m, NetworkInterface i) {}
    @Override protected void setTTL(byte ttl) {}
    @Override protected byte getTTL() { return 0; }
    @Override protected void setTimeToLive(int ttl) {}
    @Override protected int getTimeToLive() { return 0; }
}
