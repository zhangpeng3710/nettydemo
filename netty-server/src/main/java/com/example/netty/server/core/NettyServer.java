package com.example.netty.server.core;

import com.example.netty.common.plugin.DynamicPluginLoader;
import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.plugin.PluginRegistry;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.ssl.SslContextHelper;
import com.example.netty.server.handler.ServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class NettyServer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    @Value("${netty.ports.tcp}")
    private int tcpPort;

    @Value("${netty.ports.tls}")
    private int tlsPort;

    @Value("${netty.ports.short}")
    private int shortPort;

    @Autowired(required = false)
    private List<MessagePlugin> springPlugins;

    private final PluginRegistry pluginRegistry = new PluginRegistry();

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final List<Channel> serverChannels = new ArrayList<>();

    @Override
    public void run(String... args) throws Exception {
        log.info("Registering Spring-managed Netty plugins...");
        if (springPlugins != null) {
            for (MessagePlugin plugin : springPlugins) {
                pluginRegistry.register(plugin);
            }
        }

        log.info("Loading dynamic plugins from 'plugins' directory...");
        File pluginsDir = new File("plugins");
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs();
        }
        List<MessagePlugin> dynamicPlugins = DynamicPluginLoader.loadPluginsFromDir(pluginsDir.getAbsolutePath());
        for (MessagePlugin plugin : dynamicPlugins) {
            pluginRegistry.register(plugin);
        }

        log.info("Starting Netty Servers...");
        
        // 1. Start TCP Server (Long Connection)
        startServer(tcpPort, "TCP", null);

        // 2. Start TLS Server (Long Connection with TLS)
        SslContext sslContext = SslContextHelper.getServerSslContext();
        startServer(tlsPort, "TLS", sslContext);

        // 3. Start SHORT Server (Short Connection)
        startServer(shortPort, "SHORT", null);
    }

    private void startServer(int port, String type, SslContext sslContext) {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) throws Exception {
                  ChannelPipeline pipeline = ch.pipeline();

                  // If SSL Context is present, add SSL Handler first
                  if (sslContext != null) {
                      pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                  }

                  // Protobuf Frame Decoders & Encoders
                  pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder());
                  pipeline.addLast("protobufDecoder", new ProtobufDecoder(MessagePacket.getDefaultInstance()));

                  pipeline.addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender());
                  pipeline.addLast("protobufEncoder", new ProtobufEncoder());

                  // Business Handler
                  pipeline.addLast("handler", new ServerHandler(type, pluginRegistry));
              }
          });

        try {
            Channel channel = b.bind(port).sync().channel();
            serverChannels.add(channel);
            log.info("[Netty Server] {} listener started on port {}", type, port);
        } catch (InterruptedException e) {
            log.error("Failed to start Netty {} server on port {}", type, port, e);
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Netty Servers...");
        for (Channel channel : serverChannels) {
            if (channel != null) {
                channel.close();
            }
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        log.info("Netty Servers shut down successfully.");
    }
}

