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
            int connectionHandle, long remoteSteamID, int proxyPort, Screen currentScreen) {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Fresh multiplayer list as the disconnect "Back" target.
            final Screen returnScreen = new MultiplayerScreen(new MainMenuScreen());

            NetworkManager connection =
                    NetworkManager.connectToServer(
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

            connection.setListener(
                    new ClientLoginNetHandler(connection, mc, returnScreen, status -> {}));

            // Intention hostname carries the Forge modded-connection marker.
            String hostName = "SteamRelay\0" + FMLNetworkConstants.NETVERSION + "\0";
            connection.send(new CHandshakePacket(hostName, 25565, ProtocolType.LOGIN));
            connection.send(new CLoginStartPacket(mc.getUser().getGameProfile()));

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

// LoopbackBridge: buffered TCP<->Steam proxy
