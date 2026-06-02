package com.example.netty.client.core;

import com.example.netty.client.handler.ClientHandler;
import com.example.netty.common.plugin.DynamicPluginLoader;
import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.plugin.PluginRegistry;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.ssl.SslContextHelper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class NettyClient {

    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    @Value("${netty.host}")
    private String host;

    @Value("${netty.ports.tcp}")
    private int tcpPort;

    @Value("${netty.ports.tls}")
    private int tlsPort;

    @Value("${netty.ports.short}")
    private int shortPort;

    @Value("${netty.mode}")
    private String mode; // TCP, TLS, SHORT

    @Value("${netty.heartbeat-interval}")
    private int heartbeatInterval;

    @Autowired(required = false)
    private List<MessagePlugin> springPlugins;

    private final PluginRegistry pluginRegistry = new PluginRegistry();

    private final EventLoopGroup group = new NioEventLoopGroup();
    private Channel channel;

    public Channel getChannel() {
        return this.channel;
    }

    public String getMode() {
        return this.mode;
    }

    public void start() throws Exception {
        log.info("Registering Spring-managed Netty client plugins...");
        if (springPlugins != null) {
            for (MessagePlugin plugin : springPlugins) {
                pluginRegistry.register(plugin);
            }
        }

        log.info("Loading dynamic client plugins from 'plugins' directory...");
        File pluginsDir = new File("plugins");
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs();
        }
        List<MessagePlugin> dynamicPlugins = DynamicPluginLoader.loadPluginsFromDir(pluginsDir.getAbsolutePath());
        for (MessagePlugin plugin : dynamicPlugins) {
            pluginRegistry.register(plugin);
        }

        int port;
        SslContext sslContext = null;

        if ("TLS".equalsIgnoreCase(mode)) {
            port = tlsPort;
            sslContext = SslContextHelper.getClientSslContext();
            log.info("Client configured in TLS mode connecting to {}:{}", host, port);
        } else if ("SHORT".equalsIgnoreCase(mode)) {
            port = shortPort;
            log.info("Client configured in SHORT connection mode connecting to {}:{}", host, port);
        } else {
            port = tcpPort;
            log.info("Client configured in TCP long connection mode connecting to {}:{}", host, port);
        }

        Bootstrap b = new Bootstrap();
        final SslContext finalSslCtx = sslContext;
        b.group(group)
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) throws Exception {
                 ChannelPipeline pipeline = ch.pipeline();

                 // SSL first if TLS
                 if (finalSslCtx != null) {
                     pipeline.addLast("ssl", finalSslCtx.newHandler(ch.alloc(), host, port));
                 }

                 // IdleStateHandler for sending heartbeat Pings (only for long connections)
                 if (!"SHORT".equalsIgnoreCase(mode)) {
                     // 0 reader idle, heartbeatInterval writer idle (no write for N seconds triggers ping), 0 all idle
                     pipeline.addLast("idleStateHandler", new IdleStateHandler(0, heartbeatInterval, 0, TimeUnit.SECONDS));
                 }

                 // Protobuf Decoders & Encoders
                 pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder());
                 pipeline.addLast("protobufDecoder", new ProtobufDecoder(MessagePacket.getDefaultInstance()));

                 pipeline.addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender());
                 pipeline.addLast("protobufEncoder", new ProtobufEncoder());

                 // Client Handler
                 pipeline.addLast("handler", new ClientHandler(mode, pluginRegistry));
             }
         });

        ChannelFuture future = b.connect(host, port).sync();
        this.channel = future.channel();
        log.info("Netty Client started and connected successfully.");
    }

    public void stop() {
        log.info("Stopping Netty Client...");
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
        log.info("Netty Client stopped.");
    }
}

