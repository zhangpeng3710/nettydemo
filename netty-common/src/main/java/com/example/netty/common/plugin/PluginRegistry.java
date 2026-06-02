package com.example.netty.common.plugin;

import com.example.netty.common.proto.MessagePacket;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PluginRegistry {
    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private final List<MessagePlugin> plugins = new CopyOnWriteArrayList<>();

    public synchronized void register(MessagePlugin plugin) {
        log.info("Registering plugin: [{}] ({})", plugin.getId(), plugin.getClass().getName());
        plugins.add(plugin);
        plugins.sort(Comparator.comparingInt(MessagePlugin::getOrder));
        plugin.onLoad();
    }

    public synchronized void unregister(MessagePlugin plugin) {
        log.info("Unregistering plugin: [{}]", plugin.getId());
        if (plugins.remove(plugin)) {
            plugin.onUnload();
        }
    }

    public List<MessagePlugin> getPlugins() {
        return new ArrayList<>(plugins);
    }

    public void dispatchActive(ChannelHandlerContext ctx, String connectionType) {
        for (MessagePlugin plugin : plugins) {
            try {
                plugin.onChannelActive(ctx, connectionType);
            } catch (Exception e) {
                log.error("Plugin [{}] onChannelActive error", plugin.getId(), e);
            }
        }
    }

    public void dispatchInactive(ChannelHandlerContext ctx, String connectionType) {
        for (MessagePlugin plugin : plugins) {
            try {
                plugin.onChannelInactive(ctx, connectionType);
            } catch (Exception e) {
                log.error("Plugin [{}] onChannelInactive error", plugin.getId(), e);
            }
        }
    }

    public void dispatchException(ChannelHandlerContext ctx, Throwable cause, String connectionType) {
        for (MessagePlugin plugin : plugins) {
            try {
                plugin.onExceptionCaught(ctx, cause, connectionType);
            } catch (Exception e) {
                log.error("Plugin [{}] onExceptionCaught error", plugin.getId(), e);
            }
        }
    }

    public void dispatchUserEvent(ChannelHandlerContext ctx, Object evt, String connectionType) {
        for (MessagePlugin plugin : plugins) {
            try {
                plugin.onUserEventTriggered(ctx, evt, connectionType);
            } catch (Exception e) {
                log.error("Plugin [{}] onUserEventTriggered error", plugin.getId(), e);
            }
        }
    }

    public void dispatchMessage(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        boolean processed = false;
        for (MessagePlugin plugin : plugins) {
            if (plugin.supports(packet)) {
                try {
                    log.debug("Dispatching packet to plugin: [{}]", plugin.getId());
                    if (plugin.handle(ctx, packet, connectionType)) {
                        processed = true;
                        break; // Stop propagation as the plugin intercept it
                    }
                } catch (Exception e) {
                    log.error("Plugin [{}] handle message error", plugin.getId(), e);
                }
            }
        }
        if (!processed) {
            log.warn("No plugin handled packet of type: {}", packet.getType());
        }
    }
}
