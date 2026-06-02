package com.example.netty.common.plugin;

import com.example.netty.common.proto.MessagePacket;
import io.netty.channel.ChannelHandlerContext;

public interface MessagePlugin {
    /**
     * Unique identifier for the plugin.
     */
    String getId();

    /**
     * Hook called when plugin is loaded.
     */
    default void onLoad() {}

    /**
     * Hook called when plugin is unloaded.
     */
    default void onUnload() {}

    /**
     * Callback when a channel becomes active.
     */
    default void onChannelActive(ChannelHandlerContext ctx, String connectionType) throws Exception {}

    /**
     * Callback when a channel becomes inactive.
     */
    default void onChannelInactive(ChannelHandlerContext ctx, String connectionType) throws Exception {}

    /**
     * Determines whether this plugin can handle/process the given packet.
     */
    boolean supports(MessagePacket packet);

    /**
     * Handles the message.
     * @return true to intercept message propagation; false to continue propagating to subsequent plugins.
     */
    boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) throws Exception;

    /**
     * Callback when an exception is caught on the channel.
     */
    default void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause, String connectionType) throws Exception {}

    /**
     * Callback when a user event is triggered (e.g., IdleStateEvent).
     */
    default void onUserEventTriggered(ChannelHandlerContext ctx, Object evt, String connectionType) throws Exception {}

    /**
     * Order of plugin execution (lower values run first).
     */
    default int getOrder() {
        return 0;
    }
}
