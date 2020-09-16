package adbs.channel;

import io.netty.channel.*;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class AdbChannelEventLoop extends AbstractEventLoop {

    private final EventLoop eventLoop;

    public AdbChannelEventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    @Override
    public ChannelFuture register(Channel channel) {
        return register(channel, channel.newPromise());
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) {
        return register(promise.channel(), promise);
    }

    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        channel.unsafe().register(this, promise);
        return promise;
    }

    @Override
    public void shutdown() {
        //不需要实现，这个线程是沿用的
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return this.eventLoop.inEventLoop(thread);
    }

    @Override
    public boolean isShuttingDown() {
        return this.eventLoop.isShuttingDown();
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        Promise<?> promise = new DefaultPromise<>(this.eventLoop);
        promise.setSuccess(null);
        return promise;
    }

    @Override
    public Future<?> terminationFuture() {
        Promise<?> promise = new DefaultPromise<>(this.eventLoop);
        promise.setSuccess(null);
        return promise;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return true;
    }

    @Override
    public void execute(Runnable command) {
        this.eventLoop.execute(command);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return this.eventLoop.schedule(command, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return this.eventLoop.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return this.eventLoop.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return this.eventLoop.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }
}
