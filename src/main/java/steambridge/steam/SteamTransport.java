/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeMod;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraftforge.network.NetworkConstants;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
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

    private SteamTransport() {}

    static boolean createServerLoopbackBridge(int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
        b.group(NIO_GROUP)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
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

    static int allocateClientLoopbackPort(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn);
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.ServerBootstrap b = new io.netty.bootstrap.ServerBootstrap();
        b.group(NIO_GROUP)
         .channel(NioServerSocketChannel.class)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.parent().close(); // One client only
                 ch.pipeline().addLast("bridge", bridge);
             }
         });

        try {
            ChannelFuture f = b.bind("127.0.0.1", 0).syncUninterruptibly();
            if (f.isSuccess()) {
                int port = ((InetSocketAddress) f.channel().localAddress()).getPort();
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
            Screen currentScreen
    ) {
        try {
            Minecraft mc = Minecraft.getInstance();

            // The screen we hand to the net handler becomes DisconnectedScreen's parent
            // when the server later drops us. Re-showing the stale connect/add-server
            // screen the player launched from leaves its buttons unresponsive, so use a
            // fresh multiplayer list instead (same fallback vanilla uses when it has
            // no origin screen). This is the "Back to server list" target after a kick.
            final Screen returnScreen = new JoinMultiplayerScreen(new TitleScreen());

            // Connection.connect builds the full vanilla client pipeline (frame codecs,
            // packet codecs, the Connection as packet handler) and connects the socket.
            // No reflection into the channel/address fields required.
            Connection connection = new Connection(PacketFlow.CLIENTBOUND);
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", proxyPort);
            ChannelFuture connectFuture = Connection.connect(addr, false, connection).syncUninterruptibly();

            if (!connectFuture.isSuccess()) {
                SteamBridgeMod.LOG.error("[LoopbackBridge][Client] Connect to proxy {} failed.", proxyPort);
                return false;
            }

            connection.setListener(new ClientHandshakePacketListenerImpl(
                    connection, mc, null, returnScreen, false, null, status -> {}));

            // Intention hostname carries the Forge modded-connection marker ("...\0FML3\0"),
            // so the host runs the Forge login handshake instead of treating us as vanilla.
            String hostName = "SteamRelay\0" + NetworkConstants.NETVERSION + "\0";
            connection.send(new ClientIntentionPacket(hostName, 25565, ConnectionProtocol.LOGIN));
            connection.send(new ServerboundHelloPacket(
                    mc.getUser().getName(),
                    Optional.ofNullable(mc.getUser().getProfileId())));

            SteamBridgeMod.LOG.info("[LoopbackBridge][Client] Connected to loopback proxy. proxyPort={} conn={} steamID={}",
                proxyPort, connectionHandle, remoteSteamID);
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
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> preActivateQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;

    // Backpressure queue. A plain LinkedList is safe here only because Netty guarantees every
    // call into a channel's handlers (read, write, flush) runs on that channel's single event-loop
    // thread. If this queue is ever touched from outside the event loop, this needs to change.
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
     * Runnable instead of N. This is the gameplay hot path during chunk streaming.
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
