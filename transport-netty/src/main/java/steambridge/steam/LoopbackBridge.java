package steambridge.steam;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import steambridge.core.InboundMessage;
import steambridge.core.MemoryBudget;
import steambridge.core.QueueLimits;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LoopbackBridge extends ChannelInboundHandlerAdapter {
    private static final MemoryBudget PROCESS_BUDGET = new MemoryBudget(128L * 1024 * 1024);

    public interface Driver {
        int sendMessageFromByteBuf(int connection, ByteBuf data, int length);

        default steambridge.core.SendQueueState sendQueueState(int connection) {
            return steambridge.core.SendQueueState.UNKNOWN;
        }

        void onBridgeClosed(LoopbackBridge bridge, boolean closeSteam, String reason);

        default void onBridgeFinished(LoopbackBridge bridge, String reason) {
            onBridgeClosed(bridge, true, reason);
        }

        default boolean drainPeerMessages(LoopbackBridge bridge) {
            return true;
        }
    }

    private final int connection;
    private final Driver driver;
    private final long inboundLimit;
    private final long outboundLimit;
    private final MemoryBudget memory;
    private final steambridge.core.SendWindow sendWindow = new steambridge.core.SendWindow();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger finishing = new AtomicInteger();
    private final AtomicLong inboundBytes = new AtomicLong();
    private final Object inboundLock = new Object();
    private final ArrayDeque<byte[]> incoming = new ArrayDeque<byte[]>();
    private final ArrayDeque<ByteBuf> outgoing = new ArrayDeque<ByteBuf>();
    private volatile ChannelHandlerContext context;
    private volatile Channel listener;
    private boolean inboundScheduled;
    private ScheduledFuture<?> sendRetry;
    private ScheduledFuture<?> finishDeadline;
    private ScheduledFuture<?> peerReadRetry;
    private boolean peerInputExhausted;
    private String finishReason;
    private boolean linger;
    private volatile long outboundBytes;
    private volatile long blockedSince;
    private volatile long sendRetries;
    private volatile long peakQueueMicros;
    private volatile int peakPendingBytes;
    private volatile long peakInboundBytes;
    private volatile long peakOutboundBytes;

    public LoopbackBridge(int connection, Driver driver) {
        this(connection, driver, QueueLimits.INBOUND_BYTES, QueueLimits.OUTBOUND_BYTES);
    }

    public LoopbackBridge(int connection, Driver driver, long inboundLimit, long outboundLimit) {
        this(connection, driver, inboundLimit, outboundLimit, PROCESS_BUDGET);
    }

    public LoopbackBridge(
            int connection,
            Driver driver,
            long inboundLimit,
            long outboundLimit,
            MemoryBudget memory) {
        if (connection == 0 || driver == null || inboundLimit <= 0 || outboundLimit <= 0) {
            throw new IllegalArgumentException();
        }
        this.connection = connection;
        this.driver = driver;
        this.inboundLimit = inboundLimit;
        this.outboundLimit = outboundLimit;
        if (memory == null) throw new IllegalArgumentException("memory");
        this.memory = memory;
    }

    public int connectionHandle() {
        return connection;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long queuedInboundBytes() {
        return inboundBytes.get();
    }

    public long queuedOutboundBytes() {
        return outboundBytes;
    }

    public long sendRetries() {
        return sendRetries;
    }

    public String diagnosticSummary() {
        return "javaInBytes="
                + inboundBytes.get()
                + " javaOutBytes="
                + outboundBytes
                + " peakJavaInBytes="
                + peakInboundBytes
                + " peakJavaOutBytes="
                + peakOutboundBytes
                + " sendRetries="
                + sendRetries
                + " peakNativeQueueMicros="
                + peakQueueMicros
                + " peakNativePendingBytes="
                + peakPendingBytes;
    }

    public int receiveBatchLimit(int maximum) {
        if (closed.get() || finishing.get() == 1) return 0;
        long capacity =
                Math.min(memory.available(), Math.max(0L, inboundLimit - inboundBytes.get()));
        return (int) Math.min(maximum, capacity / QueueLimits.MAX_NATIVE_MESSAGE_BYTES);
    }

    public void setServerChannel(Channel channel) {
        listener = channel;
        if (closed.get() && channel != null) channel.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        context = ctx;
        Channel server = listener;
        if (server != null) server.close();
        if (closed.get() || finishing.get() != 0) {
            ctx.close();
            return;
        }
        scheduleInbound();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf)) {
            ctx.fireChannelRead(message);
            return;
        }
        ByteBuf buffer = (ByteBuf) message;
        if (closed.get() || finishing.get() != 0) {
            buffer.release();
            return;
        }
        int length = buffer.readableBytes();
        if (outboundBytes > outboundLimit - length || !memory.reserve(length)) {
            buffer.release();
            close("Outbound queue limit exceeded", true);
            return;
        }
        outgoing.addLast(buffer);
        outboundBytes += length;
        peakOutboundBytes = Math.max(peakOutboundBytes, outboundBytes);
        ctx.channel().config().setAutoRead(false);
        drainOutbound(ctx);
    }

    private void drainOutbound(ChannelHandlerContext ctx) {
        if (closed.get() || finishing.get() == 2) return;
        if (sendRetry != null && !sendRetry.isDone()) return;
        int budget = QueueLimits.SEND_BUDGET_BYTES;
        while (!outgoing.isEmpty() && budget > 0) {
            ByteBuf buffer = outgoing.peekFirst();
            if (!buffer.isReadable()) {
                outgoing.removeFirst().release();
                continue;
            }
            int length =
                    Math.min(
                            Math.min(buffer.readableBytes(), QueueLimits.SEND_CHUNK_BYTES), budget);
            int result;
            try {
                long now = System.nanoTime();
                if (sendWindow.pollDue(now)) {
                    steambridge.core.SendQueueState state = driver.sendQueueState(connection);
                    sendWindow.update(state, now);
                    if (state != null && state.known) {
                        peakQueueMicros = Math.max(peakQueueMicros, state.queueMicros);
                        peakPendingBytes = Math.max(peakPendingBytes, state.pendingReliableBytes);
                    }
                }
                length = sendWindow.limitLength(length);
                if (!sendWindow.permits(length)) {
                    retrySend(ctx);
                    return;
                }
                result = driver.sendMessageFromByteBuf(connection, buffer, length);
            } catch (RuntimeException failure) {
                close("Steam send failed: " + failure.getClass().getSimpleName(), true);
                return;
            }
            if (result == 1) {
                sendWindow.submitted(length);
                buffer.skipBytes(length);
                outboundBytes -= length;
                memory.release(length);
                budget -= length;
                blockedSince = 0L;
                if (!buffer.isReadable()) outgoing.removeFirst().release();
            } else if (result == 25) {
                retrySend(ctx);
                return;
            } else {
                close("Steam send failed: result=" + result, true);
                return;
            }
        }
        if (!outgoing.isEmpty()) {
            ctx.executor().execute(() -> drainOutbound(ctx));
        } else if (!closed.get()) {
            if (finishing.get() == 1) {
                linger = true;
                close(finishReason, true);
            } else {
                ctx.channel().config().setAutoRead(true);
                ctx.read();
            }
        }
    }

    private void retrySend(ChannelHandlerContext ctx) {
        long now = System.nanoTime();
        if (blockedSince == 0L) blockedSince = now;
        if (now - blockedSince >= QueueLimits.BACKPRESSURE_TIMEOUT_NANOS) {
            close("Steam send queue remained full", true);
            return;
        }
        sendRetries++;
        sendRetry =
                ctx.executor()
                        .schedule(
                                () -> {
                                    sendRetry = null;
                                    drainOutbound(ctx);
                                },
                                5L,
                                TimeUnit.MILLISECONDS);
    }

    public void deliverBatchFromSteam(InboundMessage[] batch) {
        if (batch == null || batch.length == 0 || closed.get() || finishing.get() == 1) return;
        boolean overflow = false;
        synchronized (inboundLock) {
            if (closed.get() || finishing.get() == 1) return;
            for (InboundMessage message : batch) {
                if (message == null) continue;
                byte[] payload = message.getData();
                if (payload == null || payload.length == 0) continue;
                if (inboundBytes.get() > inboundLimit - payload.length
                        || !memory.reserve(payload.length)) {
                    overflow = true;
                    break;
                }
                incoming.addLast(payload);
                inboundBytes.addAndGet(payload.length);
                peakInboundBytes = Math.max(peakInboundBytes, inboundBytes.get());
            }
        }
        if (overflow) close("Inbound queue limit exceeded", true);
        else scheduleInbound();
    }

    private void scheduleInbound() {
        ChannelHandlerContext ctx = context;
        if (ctx == null || closed.get()) return;
        synchronized (inboundLock) {
            if (inboundScheduled || incoming.isEmpty()) return;
            inboundScheduled = true;
        }
        ctx.executor().execute(() -> drainInbound(ctx));
    }

    private void drainInbound(ChannelHandlerContext ctx) {
        int budget = QueueLimits.SEND_BUDGET_BYTES;
        while (!closed.get()
                && ctx.channel().isActive()
                && ctx.channel().isWritable()
                && budget > 0) {
            byte[] payload;
            synchronized (inboundLock) {
                payload = incoming.pollFirst();
            }
            if (payload == null) break;
            budget -= payload.length;
            ctx.write(Unpooled.wrappedBuffer(payload))
                    .addListener(
                            future -> {
                                inboundBytes.addAndGet(-payload.length);
                                memory.release(payload.length);
                                if (!future.isSuccess() && !closed.get())
                                    close("Local TCP write failed", true);
                                else completeInboundFinish();
                            });
        }
        ctx.flush();
        synchronized (inboundLock) {
            inboundScheduled = false;
        }
        if (ctx.channel().isWritable()) scheduleInbound();
        completeInboundFinish();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable()) scheduleInbound();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (finishing.get() == 2) close(finishReason, false);
        else finish(1, "Local TCP disconnected");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (finishing.get() == 0) close("Local bridge removed", true);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
        close("Local TCP error: " + failure.getClass().getSimpleName(), true);
    }

    public void steamClosed(String reason) {
        finish(2, reason);
    }

    public void closeLocalOnly() {
        closeLocalOnly("Local bridge closed");
    }

    public void closeLocalOnly(String reason) {
        if (finishing.get() != 2) close(reason, false);
    }

    public void close() {
        close("Connection cancelled", true);
    }

    public void abortLocal() {
        close("Steam runtime stopped", false);
    }

    public void fail(String reason) {
        close(reason, true);
    }

    private void finish(int direction, String reason) {
        if (closed.get() || !finishing.compareAndSet(0, direction)) return;
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            close(reason, direction == 1);
            return;
        }
        Runnable task =
                () -> {
                    if (closed.get()) return;
                    finishReason = reason;
                    finishDeadline =
                            ctx.executor()
                                    .schedule(
                                            () -> close("Drain timeout: " + reason, direction == 1),
                                            2L,
                                            TimeUnit.SECONDS);
                    if (direction == 1) {
                        discardInbound();
                        drainOutbound(ctx);
                    } else {
                        discardOutbound();
                        pollPeerMessages(ctx);
                    }
                };
        if (ctx.executor().inEventLoop()) task.run();
        else ctx.executor().execute(task);
    }

    private void completeInboundFinish() {
        if (finishing.get() == 2 && peerInputExhausted && inboundBytes.get() == 0)
            close(finishReason, false);
    }

    private void pollPeerMessages(ChannelHandlerContext ctx) {
        if (closed.get()) return;
        try {
            peerInputExhausted = driver.drainPeerMessages(this);
        } catch (RuntimeException failure) {
            close("Peer receive failed", false);
            return;
        }
        scheduleInbound();
        completeInboundFinish();
        if (!closed.get() && !peerInputExhausted) {
            peerReadRetry =
                    ctx.executor().schedule(() -> pollPeerMessages(ctx), 5L, TimeUnit.MILLISECONDS);
        }
    }

    private void discardInbound() {
        synchronized (inboundLock) {
            byte[] payload;
            while ((payload = incoming.pollFirst()) != null) {
                inboundBytes.addAndGet(-payload.length);
                memory.release(payload.length);
            }
        }
    }

    private void discardOutbound() {
        if (sendRetry != null) {
            sendRetry.cancel(false);
            sendRetry = null;
        }
        ByteBuf buffer;
        while ((buffer = outgoing.pollFirst()) != null) buffer.release();
        memory.release(outboundBytes);
        outboundBytes = 0L;
    }

    private void close(String reason, boolean closeSteam) {
        if (!closed.compareAndSet(false, true)) return;
        Channel server = listener;
        if (server != null) server.close();
        discardInbound();
        ChannelHandlerContext ctx = context;
        Runnable cleanup =
                () -> {
                    discardOutbound();
                    if (finishDeadline != null) finishDeadline.cancel(false);
                    if (peerReadRetry != null) peerReadRetry.cancel(false);
                    if (ctx != null) ctx.close();
                    if (closeSteam && linger) driver.onBridgeFinished(this, reason);
                    else driver.onBridgeClosed(this, closeSteam, reason);
                };
        if (ctx == null || ctx.executor().inEventLoop()) cleanup.run();
        else ctx.executor().execute(cleanup);
    }
}
