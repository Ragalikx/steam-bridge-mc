package steambridge.steam;

import static org.junit.Assert.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import org.junit.Test;

import steambridge.core.MemoryBudget;
import steambridge.core.SendQueueState;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MultiplayerFlowTest {
    @Test
    public void slowFirstPlayerDoesNotBlockThirtyOneOthersAndCanResume() throws Exception {
        run(32, false);
    }

    @Test
    public void cancellingSlowPlayerDoesNotDiscardOtherPlayersData() throws Exception {
        run(8, true);
    }

    @Test
    public void nativeLimitExceededForOnePeerDoesNotBlockFourOthers() throws Exception {
        run(5, false, true);
    }

    private void run(int players, boolean cancel) throws Exception {
        run(players, cancel, false);
    }

    private void run(int players, boolean cancel, final boolean limitExceeded) throws Exception {
        final AtomicBoolean blocked = new AtomicBoolean(true);
        final ByteArrayOutputStream[] sent = new ByteArrayOutputStream[players];
        final byte[][] expected = new byte[players][64 * 1024];
        final ByteBuf[] buffers = new ByteBuf[players];
        final Channel[] channels = new Channel[players];
        final Socket[] peers = new Socket[players];
        final LoopbackBridge[] bridges = new LoopbackBridge[players];
        final long budgetBytes = 16 * 1024 * 1024;
        MemoryBudget memory = new MemoryBudget(budgetBytes);
        LoopbackBridge.Driver driver =
                new LoopbackBridge.Driver() {
                    public SendQueueState sendQueueState(int connection) {
                        if (limitExceeded) return SendQueueState.UNKNOWN;
                        return new SendQueueState(
                                true,
                                connection == 1 && blocked.get() ? 256 * 1024 : 0,
                                0,
                                8 * 1024 * 1024);
                    }

                    public int sendMessageFromByteBuf(int connection, ByteBuf data, int length) {
                        if (limitExceeded && connection == 1 && blocked.get()) return 25;
                        byte[] payload = new byte[length];
                        data.getBytes(data.readerIndex(), payload);
                        sent[connection - 1].write(payload, 0, length);
                        return 1;
                    }

                    public void onBridgeClosed(
                            LoopbackBridge bridge, boolean closeSteam, String reason) {}
                };
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try (ServerSocket listener =
                new ServerSocket(0, players, InetAddress.getByName("127.0.0.1"))) {
            for (int i = 0; i < players; i++) {
                sent[i] = new ByteArrayOutputStream();
                for (int j = 0; j < expected[i].length; j++) expected[i][j] = (byte) (i * 7 + j);
                bridges[i] =
                        new LoopbackBridge(i + 1, driver, 8 * 1024 * 1024, 8 * 1024 * 1024, memory);
                channels[i] =
                        new Bootstrap()
                                .group(group)
                                .channel(NioSocketChannel.class)
                                .handler(bridges[i])
                                .connect(listener.getLocalSocketAddress())
                                .sync()
                                .channel();
                peers[i] = listener.accept();
                buffers[i] = Unpooled.directBuffer(expected[i].length).writeBytes(expected[i]);
                final int player = i;
                channels[i]
                        .eventLoop()
                        .submit(() -> channels[player].pipeline().fireChannelRead(buffers[player]))
                        .sync();
            }
            assertEquals(0, sent[0].size());
            assertTrue(bridges[0].sendRetries() > 0);
            for (int i = 1; i < players; i++) {
                assertArrayEquals("player " + (i + 1), expected[i], sent[i].toByteArray());
                assertFalse(bridges[i].isClosed());
                assertEquals(0, buffers[i].refCnt());
            }
            if (cancel) bridges[0].abortLocal();
            else {
                blocked.set(false);
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (bridges[0].queuedOutboundBytes() != 0 && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                channels[0].eventLoop().submit(() -> {}).sync();
                assertArrayEquals(expected[0], sent[0].toByteArray());
            }
            channels[0].eventLoop().submit(() -> {}).sync();
            assertEquals(0, buffers[0].refCnt());
            assertEquals(budgetBytes, memory.available());
        } finally {
            for (int i = 0; i < players; i++) {
                if (bridges[i] != null) bridges[i].abortLocal();
                if (channels[i] != null) channels[i].close().sync();
                if (peers[i] != null) peers[i].close();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
