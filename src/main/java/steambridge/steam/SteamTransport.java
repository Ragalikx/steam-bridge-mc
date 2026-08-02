/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import steambridge.SteamBridgeMod;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.login.ClientLoginNetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.ProtocolType;
import net.minecraft.network.handshake.client.CHandshakePacket;
import net.minecraft.network.login.client.CLoginStartPacket;
import net.minecraftforge.fml.network.FMLNetworkConstants;

import java.net.InetSocketAddress;
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

            // Fresh multiplayer list as the disconnect "Back" target.
            final Screen returnScreen = new MultiplayerScreen(new MainMenuScreen());

            NetworkManager connection = NetworkManager.connectToServer(
                    java.net.InetAddress.getByName("127.0.0.1"),
                    proxyPort,
                    mc.options.useNativeTransport());

            // ClientLoginNetHandler's auth-failure path checks mc.getCurrentServer().isLan()
            // instead of taking a ServerData argument here. We never set it, so it always
            // fell through to the strict path and anyone without a real premium session
            // (offline account, cracked launcher) got kicked right after the host's auth
            // challenge, even though the Steam transport itself was healthy. Treat Steam
            // Bridge connections the same way a LAN game is treated.
            mc.setCurrentServer(new ServerData("SteamRelay", "127.0.0.1", true));

            connection.setListener(new ClientLoginNetHandler(
                    connection, mc, returnScreen, status -> {}));

            // Intention hostname carries the Forge modded-connection marker.
            String hostName = "SteamRelay\0" + FMLNetworkConstants.NETVERSION + "\0";
            connection.send(new CHandshakePacket(hostName, 25565, ProtocolType.LOGIN));
            connection.send(new CLoginStartPacket(mc.getUser().getGameProfile()));

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
                ctx.executor().schedule(() -> drainOutbound(ctx), 5, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        if (!(cause instanceof java.io.IOException)) {
            SteamBridgeMod.LOG.warn("[LoopbackBridge] Netty error conn={}: {}", connectionHandle, cause.toString());
        }
        close();
    }

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
