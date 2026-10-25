package steambridge.steam;

import static org.junit.Assert.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.junit.Test;

import steambridge.core.InboundMessage;
import steambridge.core.MemoryBudget;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

public class LoopbackBridgeTest {
    @Test
    public void peerCloseCancelsOutboundRetriesWhileWaitingForNativeTail() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closing =
                new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger invalidSends = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong started = new java.util.concurrent.atomic.AtomicLong();
        Driver driver =
                new Driver() {
                    @Override
                    public int sendMessageFromByteBuf(int connection, ByteBuf data, int length) {
                        if (closing.get()) {
                            invalidSends.incrementAndGet();
                            return 8;
                        }
                        return 25;
                    }

                    @Override
                    public boolean drainPeerMessages(LoopbackBridge bridge) {
                        return System.nanoTime() - started.get()
                                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
                    }
                };
        LoopbackBridge bridge = new LoopbackBridge(1, driver);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (ServerSocket listener =
                new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Channel channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(bridge)
                            .connect(listener.getLocalSocketAddress())
                            .sync()
                            .channel();
            try (java.net.Socket peer = listener.accept()) {
                peer.setSoTimeout(2000);
                ByteBuf pending = Unpooled.directBuffer(3).writeBytes(new byte[] {1, 2, 3});
                channel.eventLoop()
                        .submit(() -> channel.pipeline().fireChannelRead(pending))
                        .sync();
                bridge.deliverBatchFromSteam(new InboundMessage[] {message(new byte[] {7, 8})});
                channel.eventLoop()
                        .submit(
                                () -> {
                                    closing.set(true);
                                    started.set(System.nanoTime());
                                    bridge.steamClosed("Host closed");
                                })
                        .sync();
                assertEquals(7, peer.getInputStream().read());
                assertEquals(8, peer.getInputStream().read());
                assertEquals(-1, peer.getInputStream().read());
                assertTrue(channel.closeFuture().await(2000));
                channel.eventLoop().submit(() -> {}).sync();
                assertEquals(0, invalidSends.get());
                assertEquals(0, pending.refCnt());
                assertEquals(1, driver.closes.get());
            }
        } finally {
            bridge.close();
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void peerCloseWaitsForNativeTailWhenAnotherPeerOwnsMemoryBudget() throws Exception {
        MemoryBudget memory = new MemoryBudget(512 * 1024);
        assertTrue(memory.reserve(512 * 1024));
        AtomicInteger delivered = new AtomicInteger();
        Driver driver =
                new Driver() {
                    @Override
                    public boolean drainPeerMessages(LoopbackBridge bridge) {
                        if (bridge.receiveBatchLimit(1) == 0) return false;
                        if (delivered.getAndIncrement() == 0) {
                            bridge.deliverBatchFromSteam(
                                    new InboundMessage[] {message(new byte[] {42, 43})});
                            return false;
                        }
                        return true;
                    }
                };
        LoopbackBridge bridge = new LoopbackBridge(1, driver, 512 * 1024, 512 * 1024, memory);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (ServerSocket listener =
                new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Channel channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(bridge)
                            .connect(listener.getLocalSocketAddress())
                            .sync()
                            .channel();
            try (java.net.Socket peer = listener.accept()) {
                peer.setSoTimeout(2000);
                bridge.steamClosed("Host closed after kick packet");
                bridge.closeLocalOnly();
                channel.eventLoop().submit(() -> {}).sync();
                assertFalse(bridge.isClosed());
                assertEquals(0, delivered.get());
                memory.release(512 * 1024);
                assertEquals(42, peer.getInputStream().read());
                assertEquals(43, peer.getInputStream().read());
                assertEquals(-1, peer.getInputStream().read());
                assertTrue(channel.closeFuture().await(2000));
                channel.eventLoop().submit(() -> {}).sync();
                assertEquals(0L, memory.used());
                assertEquals(1, driver.closes.get());
            }
        } finally {
            bridge.close();
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void peerCloseFlushesQueuedMinecraftBytesBeforeClosingTcp() throws Exception {
        Driver driver = new Driver();
        MemoryBudget memory = new MemoryBudget(128);
        LoopbackBridge bridge = new LoopbackBridge(1, driver, 128, 128, memory);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (ServerSocket listener =
                new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            bridge.deliverBatchFromSteam(new InboundMessage[] {message(new byte[] {7, 8, 9})});
            Channel channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(bridge)
                            .connect(listener.getLocalSocketAddress())
                            .sync()
                            .channel();
            try (java.net.Socket peer = listener.accept()) {
                peer.setSoTimeout(2000);
                bridge.steamClosed("Host stopped");
                bridge.closeLocalOnly();
                java.io.InputStream input = peer.getInputStream();
                assertEquals(7, input.read());
                assertEquals(8, input.read());
                assertEquals(9, input.read());
                assertEquals(-1, input.read());
                assertTrue(channel.closeFuture().await(2000));
                channel.eventLoop().submit(() -> {}).sync();
                assertTrue(bridge.isClosed());
                assertEquals(0L, memory.used());
                assertEquals(1, driver.closes.get());
            }
        } finally {
            bridge.close();
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void tcpFinishRetriesPendingDataThenRequestsSteamLinger() throws Exception {
        Driver driver =
                new Driver() {
                    @Override
                    public void onBridgeFinished(LoopbackBridge bridge, String reason) {
                        finished.incrementAndGet();
                        super.onBridgeFinished(bridge, reason);
                    }
                };
        driver.result = 25;
        LoopbackBridge bridge = new LoopbackBridge(1, driver);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel listener = null, channel = null;
        try {
            listener =
                    new ServerBootstrap()
                            .group(group)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel channel) {}
                                    })
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(bridge)
                            .connect(listener.localAddress())
                            .sync()
                            .channel();
            final Channel active = channel;
            ByteBuf pending = Unpooled.directBuffer(3).writeBytes(new byte[] {4, 5, 6});
            active.eventLoop().submit(() -> active.pipeline().fireChannelRead(pending)).sync();
            active.close().sync();
            assertFalse(bridge.isClosed());
            driver.result = 1;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (!bridge.isClosed() && System.nanoTime() < deadline) Thread.sleep(5);
            active.eventLoop().submit(() -> {}).sync();
            assertArrayEquals(new byte[] {4, 5, 6}, driver.sent.toByteArray());
            assertEquals(0, pending.refCnt());
            assertEquals(1, driver.finished.get());
        } finally {
            bridge.close();
            if (channel != null) channel.close().sync();
            if (listener != null) listener.close().sync();
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void aggregateBudgetIsReclaimedAcrossPeers() {
        MemoryBudget budget = new MemoryBudget(16);
        LoopbackBridge first = new LoopbackBridge(1, new Driver(), 16, 16, budget);
        LoopbackBridge second = new LoopbackBridge(2, new Driver(), 16, 16, budget);
        first.deliverBatchFromSteam(new InboundMessage[] {message(new byte[12])});
        second.deliverBatchFromSteam(new InboundMessage[] {message(new byte[5])});
        assertFalse(first.isClosed());
        assertTrue(second.isClosed());
        assertEquals(12L, budget.used());
        first.close();
        assertEquals(0L, budget.used());
        for (int i = 0; i < 1000; i++) {
            LoopbackBridge next = new LoopbackBridge(i + 1, new Driver(), 16, 16, budget);
            next.deliverBatchFromSteam(new InboundMessage[] {message(new byte[16])});
            next.close();
            assertEquals(0L, budget.used());
        }
    }

    @Test
    public void blockedPeerDoesNotHoldAnotherChannel() throws Exception {
        Driver blocked = new Driver();
        blocked.result = 25;
        Driver healthy = new Driver();
        MemoryBudget budget = new MemoryBudget(128);
        LoopbackBridge slow = new LoopbackBridge(1, blocked, 128, 128, budget);
        LoopbackBridge fast = new LoopbackBridge(2, healthy, 128, 128, budget);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel listener = null, first = null, second = null;
        try {
            listener =
                    new ServerBootstrap()
                            .group(group)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel channel) {}
                                    })
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            first =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(slow)
                            .connect(listener.localAddress())
                            .sync()
                            .channel();
            second =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(fast)
                            .connect(listener.localAddress())
                            .sync()
                            .channel();
            final Channel a = first, b = second;
            ByteBuf pending = Unpooled.directBuffer(32).writeZero(32);
            a.eventLoop().submit(() -> a.pipeline().fireChannelRead(pending)).sync();
            b.eventLoop()
                    .submit(
                            () ->
                                    b.pipeline()
                                            .fireChannelRead(
                                                    Unpooled.wrappedBuffer(new byte[] {1, 2, 3})))
                    .sync();
            assertArrayEquals(new byte[] {1, 2, 3}, healthy.sent.toByteArray());
            assertEquals(32L, budget.used());
            slow.close();
            a.eventLoop().submit(() -> {}).sync();
            assertEquals(0, pending.refCnt());
            assertEquals(0L, budget.used());
        } finally {
            if (first != null) first.close().sync();
            if (second != null) second.close().sync();
            if (listener != null) listener.close().sync();
            group.shutdownGracefully().sync();
        }
    }

    private static class Driver implements LoopbackBridge.Driver {
        volatile int result = 1;
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        @Override
        public int sendMessageFromByteBuf(int connection, ByteBuf buffer, int length) {
            if (result == 1) {
                byte[] bytes = new byte[length];
                buffer.getBytes(buffer.readerIndex(), bytes);
                sent.write(bytes, 0, bytes.length);
            }
            return result;
        }

        @Override
        public void onBridgeClosed(LoopbackBridge bridge, boolean closeSteam, String reason) {
            closes.incrementAndGet();
        }
    }

    private static InboundMessage message(byte[] bytes) {
        return new InboundMessage() {
            @Override
            public int getConnection() {
                return 1;
            }

            @Override
            public byte[] getData() {
                return bytes;
            }
        };
    }

    @Test
    public void pendingDirectBufferReleasedOnCloseOnce() throws Exception {
        Driver driver = new Driver();
        driver.result = 25;
        LoopbackBridge bridge = new LoopbackBridge(1, driver);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel listener = null, channel = null;
        try {
            listener =
                    new ServerBootstrap()
                            .group(group)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel accepted) {}
                                    })
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(bridge)
                            .connect(listener.localAddress())
                            .sync()
                            .channel();
            ByteBuf buffer = Unpooled.directBuffer(32).writeZero(32);
            final Channel active = channel;
            active.eventLoop().submit(() -> active.pipeline().fireChannelRead(buffer)).sync();
            assertEquals(1, buffer.refCnt());
            bridge.close();
            bridge.close();
            active.eventLoop().submit(() -> {}).sync();
            assertEquals(0, buffer.refCnt());
            assertEquals(1, driver.closes.get());
        } finally {
            if (channel != null) channel.close().sync();
            if (listener != null) listener.close().sync();
            group.shutdownGracefully().sync();
        }
    }

    @Test
    public void earlyInboundArrivesInOrderAfterActivation() {
        Driver driver = new Driver();
        LoopbackBridge bridge = new LoopbackBridge(1, driver);
        bridge.deliverBatchFromSteam(
                new InboundMessage[] {message(new byte[] {1, 2}), message(new byte[] {3, 4})});
        EmbeddedChannel channel = new EmbeddedChannel(bridge);
        channel.runPendingTasks();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        ByteBuf buffer;
        while ((buffer = (ByteBuf) channel.readOutbound()) != null) {
            while (buffer.isReadable()) received.write(buffer.readByte());
            buffer.release();
        }
        assertArrayEquals(new byte[] {1, 2, 3, 4}, received.toByteArray());
        assertEquals(0L, bridge.queuedInboundBytes());
        channel.finish();
    }

    @Test
    public void queueOverflowClosesAndReleasesData() {
        Driver driver = new Driver();
        LoopbackBridge bridge = new LoopbackBridge(1, driver, 8, 8);
        bridge.deliverBatchFromSteam(
                new InboundMessage[] {message(new byte[8]), message(new byte[1])});
        assertTrue(bridge.isClosed());
        assertEquals(0L, bridge.queuedInboundBytes());
        assertEquals(1, driver.closes.get());
    }

    @Test
    public void heapCompositeAndSlicedOutboundPreserveBytes() {
        Driver driver = new Driver();
        LoopbackBridge bridge = new LoopbackBridge(1, driver);
        EmbeddedChannel channel = new EmbeddedChannel(bridge);
        ByteBuf heap = Unpooled.wrappedBuffer(new byte[] {0, 1, 2});
        heap.readByte();
        ByteBuf composite = Unpooled.wrappedBuffer(new byte[] {3}, new byte[] {4, 5});
        channel.writeInbound(heap, composite);
        channel.runPendingTasks();
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, driver.sent.toByteArray());
        assertEquals(0, heap.refCnt());
        assertEquals(0, composite.refCnt());
        channel.finish();
    }

    @Test
    public void cancellationBeforeAcceptClosesListeningPort() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Channel listener =
                    new ServerBootstrap()
                            .group(group)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel channel) {}
                                    })
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            int port = ((InetSocketAddress) listener.localAddress()).getPort();
            LoopbackBridge bridge = new LoopbackBridge(1, new Driver());
            bridge.setServerChannel(listener);
            bridge.close();
            assertTrue(listener.closeFuture().await(2000));
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.bind(new InetSocketAddress("127.0.0.1", port));
            }
        } finally {
            group.shutdownGracefully().sync();
        }
    }
}
