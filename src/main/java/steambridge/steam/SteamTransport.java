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
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NettyPacketDecoder;
import net.minecraft.network.NettyPacketEncoder;
import net.minecraft.network.NetworkManager;

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

    static boolean connectClientToLoopback(
            int connectionHandle, long remoteSteamID,
            int proxyPort,
            net.minecraft.client.gui.GuiScreen currentScreen
    ) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            NetworkManager[] nmHolder = new NetworkManager[1];

            // The screen we hand to the net handler becomes GuiDisconnected's parent
            // when the server later drops us. Re-showing the stale connect/add-server
            // screen the player launched from leaves its buttons unresponsive, so use a
            // fresh multiplayer list instead - the same fallback vanilla uses when it has
            // no origin screen. This is the "Back to server list" target after a kick.
            final net.minecraft.client.gui.GuiScreen returnScreen =
                    new net.minecraft.client.gui.GuiMultiplayer(
                            new net.minecraft.client.gui.GuiMainMenu());

            io.netty.bootstrap.Bootstrap bootstrap = new io.netty.bootstrap.Bootstrap()
                .group(NIO_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        NetworkManager nm = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
                        setNmChannel(nm, ch);
                        setNmSocketAddress(nm, new InetSocketAddress("SteamRelay", 25565));
                        nmHolder[0] = nm;

                        // Vanilla client pipeline - NO steam_valve
                        ch.pipeline()
                            .addLast("splitter",        new net.minecraft.network.NettyVarint21FrameDecoder())
                            .addLast("decoder",         new NettyPacketDecoder(EnumPacketDirection.CLIENTBOUND))
                            .addLast("prepender",       new net.minecraft.network.NettyVarint21FrameEncoder())
                            .addLast("encoder",         new NettyPacketEncoder(EnumPacketDirection.SERVERBOUND))
                            .addLast("packet_handler",  nm);

                        nm.setNetHandler(
                            new net.minecraft.client.network.NetHandlerLoginClient(nm, mc, returnScreen));
                    }
                });

            io.netty.channel.ChannelFuture connectFuture = bootstrap.connect("127.0.0.1", proxyPort).syncUninterruptibly();

            if (!connectFuture.isSuccess()) {
                SteamBridgeMod.LOG.error("[LoopbackBridge][Client] Connect to proxy {} failed.", proxyPort);
                return false;
            }

            NetworkManager nm = nmHolder[0];
            nm.sendPacket(new net.minecraft.network.handshake.client.C00Handshake(
                    "SteamRelay\0FML\0", 25565,
                    net.minecraft.network.EnumConnectionState.LOGIN));
            nm.sendPacket(new net.minecraft.network.login.client.CPacketLoginStart(
                    mc.getSession().getProfile()));

            SteamBridgeMod.LOG.info("[LoopbackBridge][Client] Connected to loopback proxy. proxyPort={} conn={} steamID={}",
                proxyPort, connectionHandle, remoteSteamID);
            return true;

        } catch (Throwable t) {
            SteamBridgeMod.LOG.error("[LoopbackBridge][Client] connectClientToLoopback failed: {}", t.getMessage(), t);
            return false;
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
        SteamBridgeMod.LOG.info("[LoopbackBridge] Closing conn={}, reason={}", connectionHandle, reason);
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

