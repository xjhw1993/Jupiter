package org.jupiter.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.jupiter.rpc.UnresolvedAddress;
import org.jupiter.rpc.channel.JChannelGroup;
import org.jupiter.rpc.consumer.processor.DefaultConsumerProcessor;
import org.jupiter.transport.JConnection;
import org.jupiter.transport.JOption;
import org.jupiter.transport.error.ConnectFailedException;
import org.jupiter.transport.netty.handler.IdleStateChecker;
import org.jupiter.transport.netty.handler.ProtocolDecoder;
import org.jupiter.transport.netty.handler.ProtocolEncoder;
import org.jupiter.transport.netty.handler.connector.ConnectionWatchdog;
import org.jupiter.transport.netty.handler.connector.ConnectorHandler;
import org.jupiter.transport.netty.handler.connector.ConnectorIdleStateTrigger;

import java.util.concurrent.TimeUnit;

import static org.jupiter.common.util.JConstants.WRITER_IDLE_TIME_SECONDS;

/**
 * jupiter
 * org.jupiter.transport.netty
 *
 * @author jiachun.fjc
 */
public class JNettyTcpConnector extends NettyTcpConnector {

    // handlers
    private final ConnectorIdleStateTrigger idleStateTrigger = new ConnectorIdleStateTrigger();
    private final ConnectorHandler handler = new ConnectorHandler(new DefaultConsumerProcessor());
    private final ProtocolEncoder encoder = new ProtocolEncoder();

    public JNettyTcpConnector() {}

    public JNettyTcpConnector(boolean nativeEt) {
        super(nativeEt);
    }

    public JNettyTcpConnector(int nWorkers) {
        super(nWorkers);
    }

    public JNettyTcpConnector(int nWorkers, boolean nativeEt) {
        super(nWorkers, nativeEt);
    }

    @Override
    protected void doInit() {
        // child options
        config().setOption(JOption.SO_REUSEADDR, true);
        config().setOption(JOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(3));
        // channel factory
        if (isNativeEt()) {
            bootstrap().channel(EpollSocketChannel.class);
        } else {
            bootstrap().channel(NioSocketChannel.class);
        }
    }

    @Override
    public JConnection connect(UnresolvedAddress remoteAddress, boolean async) {
        setOptions();

        Bootstrap boot = bootstrap();

        JChannelGroup group = group(remoteAddress);

        // 重连watchdog
        final ConnectionWatchdog watchdog = new ConnectionWatchdog(boot, timer, remoteAddress, group) {

            @Override
            public ChannelHandler[] handlers() {
                return new ChannelHandler[] {
                        this,
                        new IdleStateChecker(timer, 0, WRITER_IDLE_TIME_SECONDS, 0),
                        idleStateTrigger,
                        new ProtocolDecoder(),
                        encoder,
                        handler
                };
            }
        };
        watchdog.setReconnect(true);

        try {
            ChannelFuture future;
            synchronized (bootstrapLock()) {
                boot.handler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(watchdog.handlers());
                    }
                });

                future = boot.connect(remoteAddress.getHost(), remoteAddress.getPort());
            }

            // 以下代码在synchronized同步块外面是安全的
            if (!async) {
                future.sync();
            }
        } catch (Exception e) {
            throw new ConnectFailedException("the connection fails", e);
        }

        return new JConnection(remoteAddress) {

            @Override
            public void setReconnect(boolean reconnect) {
                watchdog.setReconnect(reconnect);
            }
        };
    }
}