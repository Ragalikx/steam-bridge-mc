/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.steam;

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
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import steambridge.SteamBridgeMod;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Bridges Steam connections to Minecraft via a local TCP proxy (LoopbackBridge). MC's Netty
 * pipeline connects to the proxy; the proxy forwards bytes over Steam.
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

    static boolean createServerLoopbackBridge(
            int conn, long steamID, int mcPort, IntConsumer onLocalPort) {
        LoopbackBridge bridge = new LoopbackBridge(conn, SteamManager.getInstance());
        SteamManager.getInstance().registerLoopback(conn, bridge);

        io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
        b.group(NIO_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
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
                SteamBridgeMod.LOG.info(
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
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.parent().close(); // One client only
                                ch.pipeline().addLast("bridge", bridge);
                            }
                        });

        try {
            ChannelFuture f = b.bind("127.0.0.1", 0).syncUninterruptibly();
            if (f.isSuccess()) {
                bridge.setServerChannel(f.channel());
                int port = ((InetSocketAddress) f.channel().localAddress()).getPort();
                SteamBridgeMod.LOG.info(
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
            int connectionHandle, long remoteSteamID, int proxyPort, Screen currentScreen) {
        try {
            Minecraft mc = Minecraft.getInstance();

            // The screen we hand to the net handler becomes DisconnectedScreen's parent
            // when the server later drops us. Re-showing the stale connect/add-server
            // screen the player launched from leaves its buttons unresponsive, so use a
            // fresh multiplayer list instead, the same fallback vanilla uses when it has
            // no origin screen. This is the "Back to server list" target after a kick.
            final Screen returnScreen = new JoinMultiplayerScreen(new TitleScreen());

            // Connection.connect builds the full vanilla client pipeline (frame codecs,
            // packet codecs, the Connection as packet handler) and connects the socket;
            // no reflection into the channel/address fields required.
            Connection connection = new Connection(PacketFlow.CLIENTBOUND);
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", proxyPort);
            ChannelFuture connectFuture =
                    Connection.connect(addr, false, connection).syncUninterruptibly();

            if (!connectFuture.isSuccess()) {
                SteamBridgeMod.LOG.error(
                        "[LoopbackBridge][Client] Connect to proxy {} failed.", proxyPort);
                return false;
            }

            // initiateServerboundPlayConnection sends only ClientIntentionPacket.
            // ServerboundHelloPacket must follow immediately; without it the server
            // waits indefinitely for the login hello and times out after 30s.
            // ClientHandshakePacketListenerImpl.handleHello() disconnects on a failed Mojang
            // session check unless serverData.isLan() is true. Passing null here meant anyone
            // without a real premium session (offline account, cracked launcher) got kicked
            // right after the host's auth challenge, even though the Steam transport itself
            // was healthy. Treat Steam Bridge connections the same way a LAN game is treated.
            ServerData lanLikeServerData =
                    new ServerData("SteamRelay", "127.0.0.1", ServerData.Type.LAN);
            connection.initiateServerboundPlayConnection(
                    "SteamRelay",
                    25565,
                    new ClientHandshakePacketListenerImpl(
                            connection,
                            mc,
                            lanLikeServerData,
                            returnScreen,
                            false,
                            null,
                            status -> {},
                            null));
            connection.send(
                    new ServerboundHelloPacket(
                            mc.getUser().getName(), mc.getUser().getProfileId()));

            // pendingConnection makes Minecraft.tick() call connection.tick() each game tick,
            // which drives TickablePacketListeners during the login/config phase and fires
            // handleDisconnection() if the channel closes. Field is private; use reflection.
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField("pendingConnection");
                f.setAccessible(true);
                f.set(mc, connection);
            } catch (Exception e) {
                SteamBridgeMod.LOG.warn(
                        "[LoopbackBridge][Client] Could not set pendingConnection: {}",
                        e.getMessage());
            }

            SteamBridgeMod.LOG.info(
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

// --- LoopbackBridge: buffered TCP<->Steam proxy --------------------------
