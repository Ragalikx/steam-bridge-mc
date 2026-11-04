package steambridge.core;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SteamDatagramSocketImpl extends DatagramSocketImpl implements VoiceClient {
    public interface Endpoint {
        boolean intercept(InetAddress address, int port);

        boolean activated(SteamDatagramSocketImpl socket, int port);

        void closed(SteamDatagramSocketImpl socket);

        void send(byte[] data);

        default void send(SteamDatagramSocketImpl owner, byte[] data) {
            send(data);
        }

        void bound(int port);
    }

    private final Endpoint endpoint;
    private final Object receiveLock = new Object();
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(65536);
    private final ArrayBlockingQueue<DatagramPacket> inbox =
            new ArrayBlockingQueue<DatagramPacket>(512);
    private final Map<Integer, Object> options = new HashMap<Integer, Object>();
    private final Map<String, MembershipKey> memberships = new HashMap<String, MembershipKey>();
    private final DatagramPacket poison = new DatagramPacket(new byte[0], 0);
    private volatile DatagramChannel channel;
    private volatile Selector selector;
    private volatile boolean closed;
    private volatile boolean steam;
    private volatile int timeout;
    private DatagramPacket held;
    private InetAddress boundAddress;
    private NetworkInterface multicastInterface;
    private int ttl = 1;

    public SteamDatagramSocketImpl(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    protected void create() {}

    private DatagramChannel open() throws SocketException {
        return open(null);
    }

    private synchronized DatagramChannel open(InetAddress address) throws SocketException {
        if (closed) throw new SocketException("Socket closed");
        if (channel == null) {
            try {
                boolean ipv6 =
                        address instanceof Inet6Address
                                || (address == null
                                        && Boolean.getBoolean("java.net.preferIPv6Addresses"));
                DatagramChannel fresh =
                        DatagramChannel.open(
                                ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
                try {
                    fresh.configureBlocking(false);
                    for (Map.Entry<Integer, Object> option : options.entrySet())
                        apply(fresh, option.getKey(), option.getValue());
                    fresh.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
                    if (multicastInterface != null)
                        fresh.setOption(StandardSocketOptions.IP_MULTICAST_IF, multicastInterface);
                    channel = fresh;
                } catch (Exception e) {
                    fresh.close();
                    throw e;
                }
            } catch (Exception e) {
                throw socketError(e);
            }
        }
        return channel;
    }

    @Override
    protected void bind(int port, InetAddress address) throws SocketException {
        try {
            DatagramChannel ch = open(address);
            ch.bind(new InetSocketAddress(address, port));
            InetSocketAddress local = (InetSocketAddress) ch.getLocalAddress();
            localPort = local.getPort();
            boundAddress = local.getAddress();
            if (port != 0) endpoint.bound(port);
        } catch (Exception e) {
            throw socketError(e);
        }
    }

    @Override
    protected void connect(InetAddress address, int port) throws SocketException {
        decide(address, port);
        if (!steam) {
            try {
                open(address).connect(new InetSocketAddress(address, port));
            } catch (Exception e) {
                throw socketError(e);
            }
        }
    }

    @Override
    protected void disconnect() {
        DatagramChannel ch = channel;
        if (ch != null)
            try {
                ch.disconnect();
            } catch (IOException ignored) {
            }
    }

    private synchronized void decide(InetAddress address, int port) throws SocketException {
        if (closed) throw new SocketException("Socket closed");
        if (!steam
                && address != null
                && address.isLoopbackAddress()
                && endpoint.intercept(address, port)) {
            if (!endpoint.activated(this, port)) return;
            steam = true;
            DatagramChannel ch = channel;
            channel = null;
            if (ch != null)
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
            Selector selected = selector;
            if (selected != null) selected.wakeup();
        }
    }

    @Override
    protected void send(DatagramPacket packet) throws IOException {
        decide(packet.getAddress(), packet.getPort());
        if (steam) {
            byte[] data =
                    Arrays.copyOfRange(
                            packet.getData(),
                            packet.getOffset(),
                            packet.getOffset() + packet.getLength());
            endpoint.send(this, data);
        } else {
            DatagramChannel ch = open(packet.getAddress());
            ByteBuffer data =
                    ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
            int sent = ch.send(data, new InetSocketAddress(packet.getAddress(), packet.getPort()));
            if (sent != packet.getLength()) throw new SocketException("UDP send buffer full");
        }
    }

    @Override
    protected void receive(DatagramPacket packet) throws IOException {
        synchronized (receiveLock) {
            checkOpen();
            if (held != null) {
                copy(held, packet);
                held = null;
                return;
            }
            receiveInto(packet);
        }
    }

    @Override
    protected int peekData(DatagramPacket packet) throws IOException {
        synchronized (receiveLock) {
            checkOpen();
            if (held == null) {
                DatagramPacket pending = new DatagramPacket(new byte[65536], 65536);
                receiveInto(pending);
                held = pending;
            }
            copy(held, packet);
            return packet.getPort();
        }
    }

    @Override
    protected int peek(InetAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[1], 1);
        return peekData(packet);
    }

    private void receiveInto(DatagramPacket packet) throws IOException {
        long deadline =
                timeout == 0 ? 0L : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (true) {
            checkOpen();
            if (steam) {
                try {
                    DatagramPacket incoming =
                            deadline == 0
                                    ? inbox.take()
                                    : inbox.poll(remaining(deadline), TimeUnit.NANOSECONDS);
                    checkOpen();
                    if (incoming == null) throw new SocketTimeoutException("Receive timed out");
                    if (incoming == poison) throw new SocketException("Socket closed");
                    copy(incoming, packet);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw socketError(e);
                }
            }
            DatagramChannel ch = open();
            try {
                receiveBuffer.clear();
                InetSocketAddress source = (InetSocketAddress) ch.receive(receiveBuffer);
                if (source != null) {
                    receiveBuffer.flip();
                    int size = Math.min(receiveBuffer.remaining(), packet.getLength());
                    receiveBuffer.get(packet.getData(), packet.getOffset(), size);
                    packet.setLength(size);
                    packet.setAddress(source.getAddress());
                    packet.setPort(source.getPort());
                    return;
                }
                Selector selected = selectorFor(ch);
                // A send may switch to Steam between open() and selectorFor(): it closed this
                // channel while no selector existed to wake, so never sleep on it.
                if (steam || !ch.isOpen()) continue;
                if (deadline == 0) selected.select();
                else
                    selected.select(
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline))));
                selected.selectedKeys().clear();
            } catch (ClosedChannelException e) {
                if (!steam) throw socketError(e);
            } catch (ClosedSelectorException e) {
                throw socketError(e);
            }
        }
    }

    private synchronized Selector selectorFor(DatagramChannel ch) throws IOException {
        checkOpen();
        if (selector == null || !selector.isOpen()) selector = Selector.open();
        if (ch.isOpen() && ch.keyFor(selector) == null) ch.register(selector, SelectionKey.OP_READ);
        return selector;
    }

    private static long remaining(long deadline) throws SocketTimeoutException {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new SocketTimeoutException("Receive timed out");
        return value;
    }

    public boolean enqueueFromSteam(byte[] data, InetAddress address, int port) {
        if (closed || data == null || data.length > 65507 || port < 0 || port > 65535) return false;
        DatagramPacket packet =
                new DatagramPacket(
                        data.clone(),
                        data.length,
                        address == null ? InetAddress.getLoopbackAddress() : address,
                        port);
        return inbox.offer(packet);
    }

    @Override
    public boolean receiveVoice(byte[] data, InetAddress address, int port) {
        return enqueueFromSteam(data, address, port);
    }

    @Override
    public void closeVoice() {
        closeEndpoint();
    }

    public boolean isEndpointClosed() {
        return closed;
    }

    private static void copy(DatagramPacket source, DatagramPacket target) {
        int length = Math.min(source.getLength(), target.getLength());
        System.arraycopy(
                source.getData(), source.getOffset(), target.getData(), target.getOffset(), length);
        target.setLength(length);
        target.setAddress(source.getAddress());
        target.setPort(source.getPort());
    }

    @Override
    protected synchronized void close() {
        if (closed) return;
        closed = true;
        endpoint.closed(this);
        DatagramChannel ch = channel;
        channel = null;
        if (ch != null)
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        Selector selected = selector;
        selector = null;
        if (selected != null)
            try {
                selected.close();
            } catch (IOException ignored) {
            }
        memberships.clear();
        inbox.clear();
        inbox.offer(poison);
    }

    public final void closeEndpoint() {
        close();
    }

    private void checkOpen() throws SocketException {
        if (closed) throw new SocketException("Socket closed");
    }

    @Override
    public synchronized void setOption(int id, Object value) throws SocketException {
        checkOpen();
        if (id == SocketOptions.SO_TIMEOUT) {
            if (!(value instanceof Integer) || (Integer) value < 0)
                throw new SocketException("Invalid timeout");
            timeout = (Integer) value;
            return;
        }
        if (id == SocketOptions.IP_MULTICAST_IF2) {
            if (!(value instanceof NetworkInterface))
                throw new SocketException("Invalid multicast interface");
            multicastInterface = (NetworkInterface) value;
        } else if (id == SocketOptions.IP_MULTICAST_IF) {
            if (!(value instanceof InetAddress))
                throw new SocketException("Invalid multicast address");
            multicastInterface = NetworkInterface.getByInetAddress((InetAddress) value);
            if (multicastInterface == null)
                throw new SocketException("Multicast address has no interface");
        } else {
            if (id == SocketOptions.SO_RCVBUF
                    || id == SocketOptions.SO_SNDBUF
                    || id == SocketOptions.IP_TOS) {
                if (!(value instanceof Integer)
                        || (Integer) value < (id == SocketOptions.IP_TOS ? 0 : 1)
                        || (id == SocketOptions.IP_TOS && (Integer) value > 255))
                    throw new SocketException("Invalid socket option");
            } else if (id == SocketOptions.SO_REUSEADDR
                    || id == SocketOptions.SO_BROADCAST
                    || id == SocketOptions.IP_MULTICAST_LOOP) {
                if (!(value instanceof Boolean)) throw new SocketException("Invalid socket option");
            } else throw new SocketException("Unsupported socket option: " + id);
            options.put(id, value);
        }
        try {
            if (channel != null) {
                if (id == SocketOptions.IP_MULTICAST_IF || id == SocketOptions.IP_MULTICAST_IF2)
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, multicastInterface);
                else apply(channel, id, value);
            }
        } catch (Exception e) {
            throw socketError(e);
        }
    }

    private static void apply(DatagramChannel ch, int id, Object value) throws IOException {
        switch (id) {
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
            case SocketOptions.IP_TOS:
                ch.setOption(StandardSocketOptions.IP_TOS, (Integer) value);
                break;
            case SocketOptions.IP_MULTICAST_LOOP:
                ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, !(Boolean) value);
                break;
            default:
                throw new SocketException("Unsupported socket option: " + id);
        }
    }

    @Override
    public synchronized Object getOption(int id) throws SocketException {
        checkOpen();
        if (id == SocketOptions.SO_TIMEOUT) return timeout;
        if (id == SocketOptions.SO_BINDADDR)
            return boundAddress == null ? new InetSocketAddress(0).getAddress() : boundAddress;
        if (id == SocketOptions.IP_MULTICAST_IF2) return multicastInterface;
        if (id == SocketOptions.IP_MULTICAST_IF)
            return multicastInterface == null
                    ? new InetSocketAddress(0).getAddress()
                    : multicastInterface.getInetAddresses().nextElement();
        if (channel == null) {
            if (options.containsKey(id)) return options.get(id);
            if (id == SocketOptions.SO_RCVBUF || id == SocketOptions.SO_SNDBUF) return 65536;
            if (id == SocketOptions.IP_TOS) return 0;
            if (id == SocketOptions.SO_REUSEADDR
                    || id == SocketOptions.SO_BROADCAST
                    || id == SocketOptions.IP_MULTICAST_LOOP) return false;
            throw new SocketException("Unsupported socket option: " + id);
        }
        try {
            DatagramChannel ch = open();
            switch (id) {
                case SocketOptions.SO_RCVBUF:
                    return ch.getOption(StandardSocketOptions.SO_RCVBUF);
                case SocketOptions.SO_SNDBUF:
                    return ch.getOption(StandardSocketOptions.SO_SNDBUF);
                case SocketOptions.SO_REUSEADDR:
                    return ch.getOption(StandardSocketOptions.SO_REUSEADDR);
                case SocketOptions.SO_BROADCAST:
                    return ch.getOption(StandardSocketOptions.SO_BROADCAST);
                case SocketOptions.IP_TOS:
                    return ch.getOption(StandardSocketOptions.IP_TOS);
                case SocketOptions.IP_MULTICAST_LOOP:
                    return !ch.getOption(StandardSocketOptions.IP_MULTICAST_LOOP);
                default:
                    throw new SocketException("Unsupported socket option: " + id);
            }
        } catch (Exception e) {
            throw socketError(e);
        }
    }

    @Override
    protected synchronized void setTimeToLive(int value) throws IOException {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid multicast TTL");
        checkOpen();
        ttl = value;
        if (channel != null) channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, value);
    }

    @Override
    protected int getTimeToLive() throws IOException {
        checkOpen();
        return ttl;
    }

    @Override
    protected void setTTL(byte value) throws IOException {
        setTimeToLive(value & 255);
    }

    @Override
    protected byte getTTL() throws IOException {
        return (byte) getTimeToLive();
    }

    @Override
    protected void join(InetAddress group) throws IOException {
        joinGroup(new InetSocketAddress(group, 0), null);
    }

    @Override
    protected void leave(InetAddress group) throws IOException {
        leaveGroup(new InetSocketAddress(group, 0), null);
    }

    @Override
    protected synchronized void joinGroup(SocketAddress address, NetworkInterface network)
            throws IOException {
        if (!(address instanceof InetSocketAddress))
            throw new SocketException("Invalid multicast group");
        InetAddress group = ((InetSocketAddress) address).getAddress();
        if (group == null || !group.isMulticastAddress())
            throw new SocketException("Invalid multicast group");
        NetworkInterface selected = network == null ? defaultInterface(group) : network;
        String key = group.getHostAddress() + "%" + selected.getIndex();
        if (!memberships.containsKey(key)) memberships.put(key, open(group).join(group, selected));
    }

    @Override
    protected synchronized void leaveGroup(SocketAddress address, NetworkInterface network)
            throws IOException {
        checkOpen();
        if (!(address instanceof InetSocketAddress))
            throw new SocketException("Invalid multicast group");
        InetAddress group = ((InetSocketAddress) address).getAddress();
        if (group == null) throw new SocketException("Invalid multicast group");
        NetworkInterface selected = network == null ? defaultInterface(group) : network;
        MembershipKey key = memberships.remove(group.getHostAddress() + "%" + selected.getIndex());
        if (key != null) key.drop();
    }

    private NetworkInterface defaultInterface(InetAddress group) throws SocketException {
        if (multicastInterface != null) return multicastInterface;
        if (boundAddress != null && !boundAddress.isAnyLocalAddress()) {
            NetworkInterface found = NetworkInterface.getByInetAddress(boundAddress);
            if (found != null) return found;
        }
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks.hasMoreElements()) {
            NetworkInterface candidate = networks.nextElement();
            if (!candidate.isUp() || !candidate.supportsMulticast() || candidate.isLoopback())
                continue;
            Enumeration<InetAddress> addresses = candidate.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress local = addresses.nextElement();
                if ((group instanceof Inet6Address) == (local instanceof Inet6Address))
                    return candidate;
            }
        }
        throw new SocketException("No multicast interface available");
    }

    private static SocketException socketError(Exception cause) {
        if (cause instanceof SocketException) return (SocketException) cause;
        SocketException error = new SocketException(cause.getMessage());
        error.initCause(cause);
        return error;
    }
}
