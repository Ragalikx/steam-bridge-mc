/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeMod;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.util.MessageDeserializer;
import net.minecraft.util.MessageDeserializer2;
import net.minecraft.util.MessageSerializer;
import net.minecraft.util.MessageSerializer2;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Steam transport layer - Loopback Socket architecture.
 * <p>
 * Real Netty pipelines connect to a local TCP proxy (LoopbackBridge). The proxy
 * forwards bytes directly to SteamNetworkingSockets native methods.
 * <p>
 * This provides 100% compatibility with Minecraft and forge mods expecting a raw
 * TCP channel, without needing pipeline reflection injection.
 */
public final class SteamTransport {

    static final NioEventLoopGroup NIO_GROUP = new NioEventLoopGroup(
        4,
        new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SteamBridge-Loopback-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );

    private SteamTransport() {}

    private static volatile java.lang.reflect.Field CACHED_NM_CHANNEL_FIELD  = null;
    private static volatile java.lang.reflect.Field CACHED_NM_SOCKET_FIELD   = null;

    private static java.lang.reflect.Field findFieldByType(Class<?> clazz, Class<?> type) {
        for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
            if (f.getType().isAssignableFrom(type)) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new RuntimeException("Could not find field of type " + type.getName() + " in " + clazz.getName());
    }

    private static void setNmChannel(NetworkManager nm, Channel ch) {
        try {
            if (CACHED_NM_CHANNEL_FIELD == null)
                CACHED_NM_CHANNEL_FIELD = findFieldByType(NetworkManager.class, Channel.class);
            CACHED_NM_CHANNEL_FIELD.set(nm, ch);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set NetworkManager channel field", e);
        }
    }

    private static void setNmSocketAddress(NetworkManager nm, java.net.SocketAddress addr) {
        try {
            if (CACHED_NM_SOCKET_FIELD == null)
                CACHED_NM_SOCKET_FIELD = findFieldByType(NetworkManager.class, java.net.SocketAddress.class);
            CACHED_NM_SOCKET_FIELD.set(nm, addr);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set NetworkManager socketAddress field", e);
        }
    }

    static boolean createServerLoopbackBridge(int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
        b.group(NIO_GROUP)
         .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
         .option(io.netty.channel.ChannelOption.TCP_NODELAY, true)
         .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
             @Override
             protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            io.netty.channel.ChannelFuture f = b.connect("127.0.0.1", mcPort).syncUninterruptibly();
            if (f.isSuccess()) {
                java.net.InetSocketAddress local = (java.net.InetSocketAddress) f.channel().localAddress();
                if (onLocalPort != null) onLocalPort.accept(local.getPort());
                SteamBridgeMod.LOG.info("[LoopbackBridge][Server] Bridge started: conn={} steamID={} localPort={}", conn, steamID, local.getPort());
                return true;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error("[LoopbackBridge][Server] Failed to connect to MC port {}: {}", mcPort, e.getMessage());
        }
        SteamManager.getInstance().unregisterLoopback(conn);
        return false;
    }

    static int allocateClientLoopbackPort(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.ServerBootstrap b = new io.netty.bootstrap.ServerBootstrap();
        b.group(NIO_GROUP)
         .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
         .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
         .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
             @Override
             protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                 ch.parent().close(); // One client only
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            io.netty.channel.ChannelFuture f = b.bind("127.0.0.1", 0).syncUninterruptibly();
            if (f.isSuccess()) {
                int port = ((java.net.InetSocketAddress) f.channel().localAddress()).getPort();
                SteamBridgeMod.LOG.info("[LoopbackBridge][Client] Proxy listening on port {}", port);
                return port;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error("[LoopbackBridge][Client] Failed to bind local proxy: {}", e.getMessage());
        }
        SteamManager.getInstance().unregisterLoopback(conn);
        return -1;
    }

    /**
     * Connects Minecraft's client NetworkManager to the local loopback proxy.
     * @return the NetworkManager (caller must pump {@code processReceivedPackets} every client tick
     *         on 1.7.10), or {@code null} on failure
     */
    static NetworkManager connectClientToLoopback(
            int connectionHandle, long remoteSteamID,
            int proxyPort,
            net.minecraft.client.gui.GuiScreen currentScreen
    ) {
        try {
            Minecraft mc = Minecraft.getMinecraft();

            // Parent for GuiDisconnected after a kick/drop.
            final net.minecraft.client.gui.GuiScreen returnScreen =
                    new net.minecraft.client.gui.GuiMultiplayer(
                            new net.minecraft.client.gui.GuiMainMenu());

            // Mirror NetworkManager.provideLanClient pipeline (1.7.10), target our loopback proxy.
            final NetworkManager nm = new NetworkManager(true);

            io.netty.bootstrap.Bootstrap bootstrap = new io.netty.bootstrap.Bootstrap()
                .group(NIO_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        // Match vanilla GuiConnecting timeout (FML default read timeout is 30s;
                        // 20s was killing slow Steam+login handshakes).
                        ch.pipeline()
                            .addLast("timeout",  new ReadTimeoutHandler(30))
                            .addLast("splitter", new MessageDeserializer2())
                            .addLast("decoder",  new MessageDeserializer(NetworkManager.field_152462_h))
                            .addLast("prepender", new MessageSerializer2())
                            .addLast("encoder",  new MessageSerializer(NetworkManager.field_152462_h))
                            .addLast("packet_handler", nm);
                    }
                });

            io.netty.channel.ChannelFuture connectFuture = bootstrap.connect("127.0.0.1", proxyPort).syncUninterruptibly();

            if (!connectFuture.isSuccess()) {
                SteamBridgeMod.LOG.error("[LoopbackBridge][Client] Connect to proxy {} failed.", proxyPort);
                return null;
            }

            // Fake remote for logs / disconnect UI; channelActive already bound the Netty channel.
            setNmSocketAddress(nm, new InetSocketAddress("SteamRelay", 25565));

            // NetHandlerLoginClient's auth-failure path checks
            // mc.getCurrentServerData().isOnLAN() instead of taking a ServerData argument
            // here. We never set it, so it always fell through to the strict path and
            // anyone without a real premium session (offline account, cracked launcher)
            // got kicked right after the host's auth challenge, even though the Steam
            // transport itself was healthy. Treat Steam Bridge connections the same way
            // a LAN game is treated.
            net.minecraft.client.multiplayer.ServerData lanEntry =
                    new net.minecraft.client.multiplayer.ServerData("Steam Bridge", "127.0.0.1", true);
            mc.setServerData(lanEntry);

            nm.setNetHandler(new net.minecraft.client.network.NetHandlerLoginClient(nm, mc, returnScreen));

            // Protocol 5 = Minecraft 1.7.10. "\0FML\0" marks a Forge client for the server.
            // FML client handshake (NetworkDispatcher) is started by Forge-patched
            // NetHandlerLoginClient after S02LoginSuccess — do not inject twice.
            nm.scheduleOutboundPacket(new C00Handshake(
                    5, "SteamRelay\0FML\0", 25565, EnumConnectionState.LOGIN));
            nm.scheduleOutboundPacket(new C00PacketLoginStart(mc.getSession().func_148256_e()));

            SteamBridgeMod.LOG.info("[LoopbackBridge][Client] Connected to loopback proxy. proxyPort={} conn={} steamID={}",
                proxyPort, connectionHandle, remoteSteamID);
            return nm;

        } catch (Throwable t) {
            SteamBridgeMod.LOG.error("[LoopbackBridge][Client] connectClientToLoopback failed: {}", t.getMessage(), t);
            return null;
        }
    }
}

// --- LoopbackBridge - buffered TCP<->Steam proxy --------------------------

final class LoopbackBridge extends io.netty.channel.ChannelInboundHandlerAdapter {

    private final int connectionHandle;
    private volatile io.netty.channel.ChannelHandlerContext ctx;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> preActivateQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;

    // Backpressure queue. A plain LinkedList is safe here only because Netty guarantees every
    // call into a channel's handlers (read, write, flush) runs on that channel's single event-loop
    // thread - if this queue is ever touched from outside the event loop, this needs to change.
    private final java.util.Queue<io.netty.buffer.ByteBuf> pendingOutbound = new java.util.LinkedList<>();
    private final int STEAM_MAX_CHUNK = 256 * 1024; // 256KB safe max

    LoopbackBridge(int connectionHandle) {
        this.connectionHandle = connectionHandle;
    }

    @Override
    public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
        this.ctx = ctx;
        byte[] queued;
        while ((queued = preActivateQueue.poll()) != null) {
            ctx.write(io.netty.buffer.Unpooled.wrappedBuffer(queued));
        }
        ctx.flush();
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) {
        if (!closed) {
            SteamBridgeMod.LOG.info("[LoopbackBridge] Local socket closed conn={}", connectionHandle);
            steamClosed("Local TCP disconnected");
        }
    }

    @Override
    public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof io.netty.buffer.ByteBuf) {
            pendingOutbound.add((io.netty.buffer.ByteBuf) msg);
            drainOutbound(ctx);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void drainOutbound(io.netty.channel.ChannelHandlerContext ctx) {
        boolean flowConfigured = false;
        while (!pendingOutbound.isEmpty()) {
            io.netty.buffer.ByteBuf buf = pendingOutbound.peek();
            int readable = buf.readableBytes();
            if (readable <= 0) {
                buf.release();
                pendingOutbound.poll();
                continue;
            }

            int toSend = Math.min(readable, STEAM_MAX_CHUNK);

            int r = SteamManager.getInstance().sendMessageFromByteBuf(connectionHandle, buf, toSend);
            if (r == SteamSocketsApi.RESULT_OK) {
                // Buffer space was directly read by Steam natively! No Java byte array needed!
                buf.skipBytes(toSend);
                if (!buf.isReadable()) {
                    buf.release();
                    pendingOutbound.poll();
                }
            } else if (r == SteamSocketsApi.RESULT_LIMIT_EXCEEDED) {
                // Buffer full. Pause TCP reads (Natural Backpressure)
                if (ctx.channel().config().isAutoRead()) {
                    ctx.channel().config().setAutoRead(false);
                    flowConfigured = true;
                }
                ctx.executor().schedule(() -> drainOutbound(ctx), 5, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            } else {
                SteamBridgeMod.LOG.warn("[LoopbackBridge] Steam send failed conn={} result={}", connectionHandle, r);
                close();
                return;
            }
        }

        // Output drained. Resume TCP reads
        if (!flowConfigured && !ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(true);
            ctx.read(); // Request a read immediately
        }
    }

    @Override
    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
        if (!(cause instanceof java.io.IOException)) {
            SteamBridgeMod.LOG.warn("[LoopbackBridge] Netty error conn={}: {}", connectionHandle, cause.toString());
        }
        close();
    }

    /**
     * Delivers a whole receive-batch (all for this connection) to the Netty channel in a
     * single event-loop hop: queue every message with write(), then one flush(). This
     * collapses N per-message flushes (one syscall each) into one, and allocates one
     * Runnable instead of N - the gameplay hot path during chunk streaming.
     */
    void deliverBatchFromSteam(SteamSocketsApi.ReceivedMessage[] batch) {
        if (closed || batch == null) return;
        io.netty.channel.ChannelHandlerContext c = ctx;
        if (c != null && c.channel().isActive()) {
            c.channel().eventLoop().execute(() -> {
                for (SteamSocketsApi.ReceivedMessage m : batch) {
                    if (m != null && m.getData().length > 0) {
                        c.write(io.netty.buffer.Unpooled.wrappedBuffer(m.getData()));
                    }
                }
                c.flush();
            });
        } else {
            // Pre-activation window (brief, during connect): arrays from receiveMessages
            // are freshly allocated and never reused, so no defensive copy is needed.
            for (SteamSocketsApi.ReceivedMessage m : batch) {
                if (m != null && m.getData().length > 0) {
                    preActivateQueue.add(m.getData());
                }
            }
        }
    }

    void steamClosed(String reason) {
        if (closed) return;
        closed = true;
        SteamBridgeMod.LOG.info("[LoopbackBridge] Closing conn={}, reason={}",
                connectionHandle, SteamBridgeMod.safeLog(reason));
        io.netty.channel.ChannelHandlerContext c = ctx;
        if (c != null && c.channel().isOpen()) {
            c.close();
        }
        SteamManager.getInstance().unregisterLoopback(connectionHandle);
        SteamManager.getInstance().closeConnection(connectionHandle, SteamSocketsApi.APP_CLOSE_NORMAL, reason);
    }

    void close() {
        steamClosed("Manual close");
    }
}

