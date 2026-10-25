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
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraftforge.network.NetworkConstants;

import steambridge.SteamBridgeMod;

import java.net.InetSocketAddress;
import java.util.Optional;
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
            // fresh multiplayer list instead (same fallback vanilla uses when it has
            // no origin screen). This is the "Back to server list" target after a kick.
            final Screen returnScreen = new JoinMultiplayerScreen(new TitleScreen());

            // 1.19.2: Connection.connectToServer builds the vanilla pipeline and connects.
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", proxyPort);
            Connection connection = Connection.connectToServer(addr, false);

            // ClientHandshakePacketListenerImpl's auth-failure path checks
            // mc.getCurrentServer().isLan() instead of taking a ServerData argument here.
            // We never set it, so it always fell through to the strict path and anyone
            // without a real premium session (offline account, cracked launcher) got kicked
            // right after the host's auth challenge, even though the Steam transport itself
            // was healthy. Treat Steam Bridge connections the same way a LAN game is treated.
            mc.setCurrentServer(new ServerData("SteamRelay", "127.0.0.1", true));

            connection.setListener(
                    new ClientHandshakePacketListenerImpl(
                            connection, mc, returnScreen, status -> {}));

            // Intention hostname carries the Forge modded-connection marker so the host
            // runs the Forge login handshake instead of treating us as vanilla.
            String hostName = "SteamRelay\0" + NetworkConstants.NETVERSION + "\0";
            connection.send(new ClientIntentionPacket(hostName, 25565, ConnectionProtocol.LOGIN));
            connection.send(
                    new ServerboundHelloPacket(
                            mc.getUser().getName(),
                            Optional.empty(),
                            Optional.ofNullable(mc.getUser().getProfileId())));

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

// LoopbackBridge: buffered TCP<->Steam proxy
