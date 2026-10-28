/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
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
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;

import steambridge.SteamBridgeMod;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Bridges Steam connections to Minecraft via a local proxy (LoopbackBridge).
 *
 * <ul>
 *   <li>Host: TCP client into the integrated server's open-to-LAN port
 *   <li>Client: Netty {@link LocalChannel} (same transport as singleplayer) so we never depend on
 *       Windows localhost TCP between two Netty groups
 * </ul>
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

    /** Event loops for client-side LocalChannel proxy (must not use NioEventLoopGroup). */
    private static final EventLoopGroup LOCAL_GROUP =
            new DefaultEventLoopGroup(
                    2,
                    new ThreadFactory() {
                        private final AtomicInteger n = new AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "SteamBridge-Local-" + n.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private SteamTransport() {}

    static boolean createServerLoopbackBridge(
            int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn, SteamManager.getInstance());
        SteamManager.getInstance().registerLoopback(conn, bridge);

        Bootstrap b = new Bootstrap();
        b.group(NIO_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(
                        new ChannelInitializer<SocketChannel>() {
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

    /**
     * Bind an in-JVM {@link LocalServerChannel} for the joining client.
     *
     * @return local address id string, or null on failure
     */
    static SocketAddress allocateClientLoopbackEndpoint(int conn) {
        LoopbackBridge bridge = new LoopbackBridge(conn, SteamManager.getInstance());
        SteamManager.getInstance().registerLoopback(conn, bridge);

        // Unique address per connection handle (unsigned so negatives stay valid path ids).
        LocalAddress addr = new LocalAddress("steambridge-" + Integer.toUnsignedString(conn));

        ServerBootstrap b = new ServerBootstrap();
        b.group(LOCAL_GROUP)
                .channel(LocalServerChannel.class)
                .childHandler(
                        new ChannelInitializer<LocalChannel>() {
                            @Override
                            protected void initChannel(LocalChannel ch) {
                                SteamBridgeMod.LOG.debug(
                                        "[LoopbackBridge][Client] Accepted local MC connection:"
                                                + " local={} remote={}",
                                        ch.localAddress(),
                                        ch.remoteAddress());
                                ch.pipeline().addLast("bridge", bridge);
                            }
                        });

        try {
            ChannelFuture f = b.bind(addr).syncUninterruptibly();
            if (f.isSuccess()) {
                bridge.setServerChannel(f.channel());
                SteamBridgeMod.LOG.debug("[LoopbackBridge][Client] Local proxy bound: {}", addr);
                return addr;
            }
        } catch (Exception e) {
            SteamBridgeMod.LOG.error(
                    "[LoopbackBridge][Client] Failed to bind local proxy: {}", e.getMessage(), e);
        }
        bridge.closeLocalOnly("Local connection failed");
        return null;
    }

    static boolean connectClientToLoopback(
            int connectionHandle,
            long remoteSteamID,
            SocketAddress proxyAddress,
            Screen currentScreen,
            SteamClient steamClient) {
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

            connection.setPacketListener(
                    new ClientLoginNetworkHandler(
                            connection,
                            mc,
                            returnScreen,
                            status -> {
                                if (status != null && steamClient != null) {
                                    steamClient.setStatusMsg(status.getString());
                                }
                            }));

            // Port in the intention packet is unused for LocalChannel routing.
            connection.send(new HandshakeC2SPacket("127.0.0.1", 25565, NetworkState.LOGIN));
            connection.send(new LoginHelloC2SPacket(mc.getSession().getProfile()));

            SteamBridgeMod.LOG.debug(
                    "[LoopbackBridge][Client] Connected via LocalChannel. addr={} conn={}"
                            + " steamID={} open={} local={}",
                    proxyAddress,
                    connectionHandle,
                    remoteSteamID,
                    connection.isOpen(),
                    connection.isLocal());
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

// LoopbackBridge: buffered local/TCP <-> Steam proxy
