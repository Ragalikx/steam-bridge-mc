/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import net.minecraft.client.Minecraft;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;

import steambridge.SteamBridgeMod;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Steam transport layer - Loopback Socket architecture.
 *
 * <p>Real Netty pipelines connect to a local TCP proxy (LoopbackBridge). The proxy forwards bytes
 * directly to SteamNetworkingSockets native methods.
 *
 * <p>This provides 100% compatibility with Minecraft and forge mods expecting a raw TCP channel,
 * without needing pipeline reflection injection.
 */
public final class SteamTransport {

    static final NioEventLoopGroup NIO_GROUP =
            new NioEventLoopGroup(
                    4,
                    new ThreadFactory() {
                        private final AtomicInteger n = new AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "SteamBridge-Loopback-" + n.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private SteamTransport() {}

    private static volatile java.lang.reflect.Field CACHED_NM_CHANNEL_FIELD = null;
    private static volatile java.lang.reflect.Field CACHED_NM_SOCKET_FIELD = null;

    private static java.lang.reflect.Field findFieldByType(Class<?> clazz, Class<?> type) {
        for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
            if (f.getType().isAssignableFrom(type)) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new RuntimeException(
                "Could not find field of type " + type.getName() + " in " + clazz.getName());
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
                CACHED_NM_SOCKET_FIELD =
                        findFieldByType(NetworkManager.class, java.net.SocketAddress.class);
            CACHED_NM_SOCKET_FIELD.set(nm, addr);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set NetworkManager socketAddress field", e);
        }
    }

    static boolean createServerLoopbackBridge(
            int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn, SteamManager.getInstance());
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
        b.group(NIO_GROUP)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .option(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                .handler(
                        new io.netty.channel.ChannelInitializer<
                                io.netty.channel.socket.SocketChannel>() {
                            @Override
                            protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                                ch.pipeline().addLast("bridge", bridge);
                            }
                        });

        try {
            io.netty.channel.ChannelFuture f = b.connect("127.0.0.1", mcPort).syncUninterruptibly();
            if (f.isSuccess()) {
                java.net.InetSocketAddress local =
                        (java.net.InetSocketAddress) f.channel().localAddress();
                if (onLocalPort != null) onLocalPort.accept(local.getPort());
                SteamBridgeMod.LOG.debug(
                        "[LoopbackBridge][Server] Bridge started: conn={} steamID={} localPort={}",
                        conn,
                        steamID,
                        local.getPort());
                return true;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                    "[LoopbackBridge][Server] Failed to connect to MC port {}: {}",
                    mcPort,
                    e.getMessage());
        }
        bridge.closeLocalOnly("Local connection failed");
        return false;
    }

    static int allocateClientLoopbackPort(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn, SteamManager.getInstance());
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.ServerBootstrap b = new io.netty.bootstrap.ServerBootstrap();
        b.group(NIO_GROUP)
                .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                .childHandler(
                        new io.netty.channel.ChannelInitializer<
                                io.netty.channel.socket.SocketChannel>() {
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
                SteamBridgeMod.LOG.debug(
                        "[LoopbackBridge][Client] Proxy listening on port {}", port);
                return port;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                    "[LoopbackBridge][Client] Failed to bind local proxy: {}", e.getMessage());
        }
        bridge.closeLocalOnly("Local connection failed");
        return -1;
    }

    static boolean connectClientToLoopback(
            int connectionHandle,
            long remoteSteamID,
            int proxyPort,
            net.minecraft.client.gui.GuiScreen currentScreen) {
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

            io.netty.bootstrap.Bootstrap bootstrap =
                    new io.netty.bootstrap.Bootstrap()
                            .group(NIO_GROUP)
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.TCP_NODELAY, true)
                            .handler(
                                    new ChannelInitializer<NioSocketChannel>() {
                                        @Override
                                        protected void initChannel(NioSocketChannel ch)
                                                throws Exception {
                                            NetworkManager nm =
                                                    new NetworkManager(
                                                            EnumPacketDirection.CLIENTBOUND);
                                            setNmChannel(nm, ch);
                                            setNmSocketAddress(
                                                    nm, new InetSocketAddress("SteamRelay", 25565));
                                            nmHolder[0] = nm;

                                            // Vanilla client pipeline (1.8.9 class names) - NO
                                            // steam_valve
                                            ch.pipeline()
                                                    .addLast(
                                                            "splitter",
                                                            new net.minecraft.util
                                                                    .MessageDeserializer2())
                                                    .addLast(
                                                            "decoder",
                                                            new net.minecraft.util
                                                                    .MessageDeserializer(
                                                                    EnumPacketDirection
                                                                            .CLIENTBOUND))
                                                    .addLast(
                                                            "prepender",
                                                            new net.minecraft.util
                                                                    .MessageSerializer2())
                                                    .addLast(
                                                            "encoder",
                                                            new net.minecraft.util
                                                                    .MessageSerializer(
                                                                    EnumPacketDirection
                                                                            .SERVERBOUND))
                                                    .addLast("packet_handler", nm);

                                            // NetHandlerLoginClient's auth-failure path checks
                                            // mc.getCurrentServerData().isOnLAN() instead of taking
                                            // a ServerData
                                            // argument here. We never set it, so it always fell
                                            // through to the
                                            // strict path and anyone without a real premium session
                                            // (offline
                                            // account, cracked launcher) got kicked right after the
                                            // host's auth
                                            // challenge, even though the Steam transport itself was
                                            // healthy.
                                            // Treat Steam Bridge connections the same way a LAN
                                            // game is treated.
                                            net.minecraft.client.multiplayer.ServerData lanEntry =
                                                    new net.minecraft.client.multiplayer.ServerData(
                                                            "Steam Bridge", "127.0.0.1", true);
                                            mc.setServerData(lanEntry);

                                            nm.setNetHandler(
                                                    new net.minecraft.client.network
                                                            .NetHandlerLoginClient(
                                                            nm, mc, returnScreen));
                                        }
                                    });

            io.netty.channel.ChannelFuture connectFuture =
                    bootstrap.connect("127.0.0.1", proxyPort).syncUninterruptibly();

            if (!connectFuture.isSuccess()) {
                SteamBridgeMod.LOG.error(
                        "[LoopbackBridge][Client] Connect to proxy {} failed.", proxyPort);
                return false;
            }

            NetworkManager nm = nmHolder[0];
            // Protocol 47 = Minecraft 1.8.x; FML marker required for Forge handshake.
            nm.sendPacket(
                    new net.minecraft.network.handshake.client.C00Handshake(
                            47,
                            "SteamRelay",
                            25565,
                            net.minecraft.network.EnumConnectionState.LOGIN,
                            true));
            nm.sendPacket(
                    new net.minecraft.network.login.client.C00PacketLoginStart(
                            mc.getSession().getProfile()));

            SteamBridgeMod.LOG.debug(
                    "[LoopbackBridge][Client] Connected to loopback proxy. proxyPort={} conn={}"
                            + " steamID={}",
                    proxyPort,
                    connectionHandle,
                    remoteSteamID);
            return true;

        } catch (Throwable t) {
            SteamBridgeMod.LOG.error(
                    "[LoopbackBridge][Client] connectClientToLoopback failed: {}",
                    t.getMessage(),
                    t);
            return false;
        }
    }
}

// --- LoopbackBridge - buffered TCP<->Steam proxy --------------------------
