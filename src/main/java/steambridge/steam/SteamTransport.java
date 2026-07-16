/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeMod;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Bridges Steam connections to Minecraft via a local TCP proxy (LoopbackBridge).
 * MC's Netty pipeline connects to the proxy; the proxy forwards bytes over Steam.
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

    /** Boss group only accepts; workers handle bridge traffic (avoids parent-close races). */
    private static final EventLoopGroup CLIENT_BOSS_GROUP = new NioEventLoopGroup(
        1,
        r -> {
            Thread t = new Thread(r, "SteamBridge-Loopback-Boss");
            t.setDaemon(true);
            return t;
        }
    );

    private SteamTransport() {}

    static boolean createServerLoopbackBridge(int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        Bootstrap b = new Bootstrap();
        b.group(NIO_GROUP)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.SO_KEEPALIVE, true)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            ChannelFuture f = b.connect("127.0.0.1", mcPort).syncUninterruptibly();
            if (f.isSuccess()) {
                InetSocketAddress local = (InetSocketAddress) f.channel().localAddress();
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

    /**
     * Bind a localhost TCP proxy for the client. Returns the bound port, or -1 on failure.
     * The listen socket is kept open until the Steam connection ends (do not close parent
     * from initChannel - that was racing and killing the accepted child on some setups).
     */
    static int allocateClientLoopbackPort(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        ServerBootstrap b = new ServerBootstrap();
        b.group(CLIENT_BOSS_GROUP, NIO_GROUP)
         .channel(NioServerSocketChannel.class)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 SteamBridgeMod.LOG.info(
                     "[LoopbackBridge][Client] Accepted MC connection: local={} remote={}",
                     ch.localAddress(), ch.remoteAddress());
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            ChannelFuture f = b.bind(new InetSocketAddress("127.0.0.1", 0)).syncUninterruptibly();
            if (f.isSuccess()) {
                Channel serverChannel = f.channel();
                bridge.setServerChannel(serverChannel);
                int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
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
            Screen currentScreen,
            SteamClient steamClient
    ) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();

            // Fresh multiplayer list as the disconnect "Back" target.
            final Screen returnScreen = new MultiplayerScreen(new TitleScreen());

            // Mark as LAN so offline/invalid Mojang sessions soft-fail joinServer
            // (same path as "Direct connect" to a local world), not hard-disconnect.
            ServerInfo lanEntry = new ServerInfo("Steam Bridge", "127.0.0.1", true);
            mc.setCurrentServerEntry(lanEntry);

            // Always NIO for the Steam proxy. Epoll/native has been a source of flaky
            // localhost loops on some Windows + Fabric setups; Fabric 1.19.2 also forces false.
            ClientConnection connection = ClientConnection.connect(
                    java.net.InetAddress.getByName("127.0.0.1"),
                    proxyPort,
                    false);

            if (steamClient != null) {
                steamClient.setPendingConnection(connection);
            }

            connection.setPacketListener(new ClientLoginNetworkHandler(
                    connection, mc, returnScreen, status -> {
                        if (status != null && steamClient != null) {
                            steamClient.setStatusMsg(status.getString());
                        }
                    }));

            connection.send(new HandshakeC2SPacket("127.0.0.1", proxyPort, NetworkState.LOGIN));
            connection.send(new LoginHelloC2SPacket(mc.getSession().getProfile()));

            SteamBridgeMod.LOG.info(
                "[LoopbackBridge][Client] Connected to loopback proxy. proxyPort={} conn={} steamID={} open={}",
                proxyPort, connectionHandle, remoteSteamID, connection.isOpen());
            return true;

        } catch (Throwable t) {
            SteamBridgeMod.LOG.error("[LoopbackBridge][Client] connectClientToLoopback failed: {}", t.getMessage(), t);
            return false;
        }
    }
}

// LoopbackBridge: buffered TCP<->Steam proxy

final class LoopbackBridge extends io.netty.channel.ChannelInboundHandlerAdapter {

    private final int connectionHandle;
    private volatile io.netty.channel.ChannelHandlerContext ctx;
    private volatile Channel serverChannel;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> preActivateQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;

    private final java.util.Queue<io.netty.buffer.ByteBuf> pendingOutbound = new java.util.LinkedList<>();
    private final int STEAM_MAX_CHUNK = 256 * 1024; // 256KB safe max

    LoopbackBridge(int connectionHandle) {
        this.connectionHandle = connectionHandle;
    }

    void setServerChannel(Channel serverChannel) {
        this.serverChannel = serverChannel;
    }

    @Override
    public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
        this.ctx = ctx;
        SteamBridgeMod.LOG.info("[LoopbackBridge] channelActive conn={} remote={}", connectionHandle, ctx.channel().remoteAddress());
        byte[] queued;
        while ((queued = preActivateQueue.poll()) != null) {
            // Defensive copy: Steam payload arrays must not be mutated later.
            ctx.write(Unpooled.copiedBuffer(queued));
        }
        ctx.flush();
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) {
        if (!closed) {
            SteamBridgeMod.LOG.info(
                "[LoopbackBridge] Local socket closed conn={} active={} open={}",
                connectionHandle, ctx.channel().isActive(), ctx.channel().isOpen());
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
                buf.skipBytes(toSend);
                if (!buf.isReadable()) {
                    buf.release();
                    pendingOutbound.poll();
                }
            } else if (r == SteamSocketsApi.RESULT_LIMIT_EXCEEDED) {
                if (ctx.channel().config().isAutoRead()) {
                    ctx.channel().config().setAutoRead(false);
                    flowConfigured = true;
                }
                ctx.executor().schedule(() -> drainOutbound(ctx), 5, TimeUnit.MILLISECONDS);
                return;
            } else {
                SteamBridgeMod.LOG.warn("[LoopbackBridge] Steam send failed conn={} result={}", connectionHandle, r);
                close();
                return;
            }
        }

        if (!flowConfigured && !ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(true);
            ctx.read();
        }
    }

    @Override
    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
        SteamBridgeMod.LOG.warn(
            "[LoopbackBridge] Netty error conn={}: {}",
            connectionHandle, cause.toString());
        close();
    }

    void deliverBatchFromSteam(SteamSocketsApi.ReceivedMessage[] batch) {
        if (closed || batch == null) return;
        io.netty.channel.ChannelHandlerContext c = ctx;
        if (c != null && c.channel().isActive()) {
            c.channel().eventLoop().execute(() -> {
                if (closed) return;
                for (SteamSocketsApi.ReceivedMessage m : batch) {
                    if (m != null && m.getData().length > 0) {
                        // Copy: receive buffers may be reused after this callback returns.
                        c.write(Unpooled.copiedBuffer(m.getData()));
                    }
                }
                c.flush();
            });
        } else {
            for (SteamSocketsApi.ReceivedMessage m : batch) {
                if (m != null && m.getData().length > 0) {
                    byte[] copy = new byte[m.getData().length];
                    System.arraycopy(m.getData(), 0, copy, 0, copy.length);
                    preActivateQueue.add(copy);
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
        Channel sc = serverChannel;
        serverChannel = null;
        if (sc != null && sc.isOpen()) {
            sc.close();
        }
        SteamManager.getInstance().unregisterLoopback(connectionHandle);
        SteamManager.getInstance().closeConnection(connectionHandle, SteamSocketsApi.APP_CLOSE_NORMAL, reason);
    }

    /** Close only the local Netty TCP leg (Steam peer already gone). */
    void closeLocalOnly(String reason) {
        if (closed) return;
        closed = true;
        SteamBridgeMod.LOG.info("[LoopbackBridge] Closing local TCP conn={}, reason={}", connectionHandle, reason);
        io.netty.channel.ChannelHandlerContext c = ctx;
        if (c != null && c.channel().isOpen()) {
            c.close();
        }
        Channel sc = serverChannel;
        serverChannel = null;
        if (sc != null && sc.isOpen()) {
            sc.close();
        }
        SteamManager.getInstance().unregisterLoopback(connectionHandle);
    }

    void close() {
        steamClosed("Manual close");
    }
}
