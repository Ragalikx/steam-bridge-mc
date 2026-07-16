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
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
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
import java.net.SocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Bridges Steam connections to Minecraft via a local proxy (LoopbackBridge).
 * <ul>
 *   <li>Host: TCP client into the integrated server's open-to-LAN port</li>
 *   <li>Client: Netty {@link LocalChannel} (same transport as singleplayer) so we never
 *       depend on Windows localhost TCP between two Netty groups</li>
 * </ul>
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

    /** Event loops for client-side LocalChannel proxy (must not use NioEventLoopGroup). */
    private static final EventLoopGroup LOCAL_GROUP = new DefaultEventLoopGroup(
        2,
        new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SteamBridge-Local-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
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
                SteamBridgeMod.LOG.info(
                    "[LoopbackBridge][Server] Bridge started: conn={} steamID={} localPort={}",
                    conn, steamID, local.getPort());
                return true;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                "[LoopbackBridge][Server] Failed to connect to MC port {}: {}", mcPort, e.getMessage());
        }
        SteamManager.getInstance().unregisterLoopback(conn);
        return false;
    }

    /**
     * Bind an in-JVM {@link LocalServerChannel} for the joining client.
     * @return local address id string, or null on failure
     */
    static SocketAddress allocateClientLoopbackEndpoint(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        // Unique address per connection handle (unsigned so negatives stay valid path ids).
        LocalAddress addr = new LocalAddress("steambridge-" + Integer.toUnsignedString(conn));

        ServerBootstrap b = new ServerBootstrap();
        b.group(LOCAL_GROUP)
         .channel(LocalServerChannel.class)
         .childHandler(new ChannelInitializer<LocalChannel>() {
             @Override
             protected void initChannel(LocalChannel ch) {
                 SteamBridgeMod.LOG.info(
                     "[LoopbackBridge][Client] Accepted local MC connection: local={} remote={}",
                     ch.localAddress(), ch.remoteAddress());
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            ChannelFuture f = b.bind(addr).syncUninterruptibly();
            if (f.isSuccess()) {
                bridge.setServerChannel(f.channel());
                SteamBridgeMod.LOG.info("[LoopbackBridge][Client] Local proxy bound: {}", addr);
                return addr;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                "[LoopbackBridge][Client] Failed to bind local proxy: {}", e.getMessage(), e);
        }
        SteamManager.getInstance().unregisterLoopback(conn);
        return null;
    }

    static boolean connectClientToLoopback(
            int connectionHandle, long remoteSteamID,
            SocketAddress proxyAddress,
            Screen currentScreen,
            SteamClient steamClient
    ) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();

            final Screen returnScreen = new MultiplayerScreen(new TitleScreen());

            // LAN flag: soft-fail Mojang session join for offline/cracked friends.
            ServerInfo lanEntry = new ServerInfo("Steam Bridge", "127.0.0.1", true);
            mc.setCurrentServerEntry(lanEntry);

            // Same transport Minecraft uses for singleplayer / integrated server.
            // Avoids Windows localhost TCP between two Netty groups (was dying with reason='').
            ClientConnection connection = ClientConnection.connectLocal(proxyAddress);

            if (steamClient != null) {
                steamClient.setPendingConnection(connection);
            }

            connection.setPacketListener(new ClientLoginNetworkHandler(
                    connection, mc, returnScreen, status -> {
                        if (status != null && steamClient != null) {
                            steamClient.setStatusMsg(status.getString());
                        }
                    }));

            // Port in the intention packet is unused for LocalChannel routing.
            connection.send(new HandshakeC2SPacket("127.0.0.1", 25565, NetworkState.LOGIN));
            connection.send(new LoginHelloC2SPacket(mc.getSession().getProfile()));

            SteamBridgeMod.LOG.info(
                "[LoopbackBridge][Client] Connected via LocalChannel. addr={} conn={} steamID={} open={} local={}",
                proxyAddress, connectionHandle, remoteSteamID, connection.isOpen(), connection.isLocal());
            return true;

        } catch (Throwable t) {
            SteamBridgeMod.LOG.error(
                "[LoopbackBridge][Client] connectClientToLoopback failed: {}", t.getMessage(), t);
            return false;
        }
    }
}

// LoopbackBridge: buffered local/TCP <-> Steam proxy

final class LoopbackBridge extends io.netty.channel.ChannelInboundHandlerAdapter {

    private final int connectionHandle;
    private volatile io.netty.channel.ChannelHandlerContext ctx;
    private volatile Channel serverChannel;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> preActivateQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;

    private final java.util.Queue<io.netty.buffer.ByteBuf> pendingOutbound = new java.util.LinkedList<>();
    private final int STEAM_MAX_CHUNK = 256 * 1024;

    LoopbackBridge(int connectionHandle) {
        this.connectionHandle = connectionHandle;
    }

    void setServerChannel(Channel serverChannel) {
        this.serverChannel = serverChannel;
    }

    @Override
    public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
        this.ctx = ctx;
        SteamBridgeMod.LOG.info(
            "[LoopbackBridge] channelActive conn={} remote={}",
            connectionHandle, ctx.channel().remoteAddress());
        byte[] queued;
        while ((queued = preActivateQueue.poll()) != null) {
            ctx.write(Unpooled.copiedBuffer(queued));
        }
        ctx.flush();
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) {
        if (!closed) {
            SteamBridgeMod.LOG.info(
                "[LoopbackBridge] Local channel closed conn={} active={} open={}",
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
                SteamBridgeMod.LOG.warn(
                    "[LoopbackBridge] Steam send failed conn={} result={}", connectionHandle, r);
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
            "[LoopbackBridge] Netty error conn={}: {}", connectionHandle, cause.toString());
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
        SteamBridgeMod.LOG.info(
            "[LoopbackBridge] Closing conn={}, reason={}",
                connectionHandle, SteamBridgeMod.safeLog(reason));
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
        SteamManager.getInstance().closeConnection(
            connectionHandle, SteamSocketsApi.APP_CLOSE_NORMAL, reason);
    }

    void closeLocalOnly(String reason) {
        if (closed) return;
        closed = true;
        SteamBridgeMod.LOG.info(
            "[LoopbackBridge] Closing local channel conn={}, reason={}", connectionHandle, reason);
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
