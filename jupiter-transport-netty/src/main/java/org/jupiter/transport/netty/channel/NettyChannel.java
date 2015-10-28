package org.jupiter.transport.netty.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.jupiter.rpc.channel.JChannel;
import org.jupiter.rpc.channel.JFutureListener;

import java.net.SocketAddress;

/**
 * 对netty {@link Channel}的包装, 通过静态方法 {@link NettyChannel#attachChannel(Channel)} 获取一个实例,
 * {@link NettyChannel} 实例构造后会attach到对应 {@link Channel}上, 不需要每次创建.
 *
 * jupiter
 * org.jupiter.transport.netty.channel
 *
 * @author jiachun.fjc
 */
public class NettyChannel implements JChannel {

    private static final AttributeKey<NettyChannel> NETTY_CHANNEL_KEY = AttributeKey.valueOf("NettyChannel");

    /**
     * Get the {@link NettyChannel} for the given {@link Channel}. This method will never return null.
     */
    public static NettyChannel attachChannel(Channel channel) {
        Attribute<NettyChannel> attr = channel.attr(NETTY_CHANNEL_KEY);
        NettyChannel ch = attr.get();
        if (ch == null) {
            NettyChannel newCh = new NettyChannel(channel);
            ch = attr.setIfAbsent(newCh);
            if (ch == null) {
                ch = newCh;
            }
        }
        return ch;
    }

    private final Channel channel;

    private NettyChannel(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    @Override
    public String id() {
        return channel.id().asShortText(); // 注意这里的id并不是全局唯一, 单节点中是唯一的
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public boolean isIoThread() {
        return channel.eventLoop().inEventLoop();
    }

    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public JChannel close() {
        channel.close();
        return this;
    }

    @Override
    public JChannel close(final JFutureListener<JChannel> listener) {
        final JChannel ch = this;
        channel.close().addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                listener.operationComplete(ch, future.isSuccess());
            }
        });
        return ch;
    }

    @Override
    public JChannel write(Object msg) {
        channel.writeAndFlush(msg);
        return this;
    }

    @Override
    public JChannel write(Object msg, final JFutureListener<JChannel> listener) {
        final JChannel ch = this;
        channel.writeAndFlush(msg)
                .addListener(new ChannelFutureListener() {

                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        listener.operationComplete(ch, future.isSuccess());
                    }
                });
        return ch;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof NettyChannel && channel.equals(((NettyChannel) obj).channel));
    }

    @Override
    public int hashCode() {
        return channel.hashCode();
    }

    @Override
    public String toString() {
        return channel.toString();
    }
}