package dev.nickyllo.betterbuild.mc;

import dev.nickyllo.betterbuild.core.platform.ChatSink;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends the Architect's words to a player, formatted like another player speaking
 * rather than like a system message — he is a character, not a command output.
 */
public final class PlayerChatSink implements ChatSink {

    private final ServerPlayer player;
    private final String name;

    public PlayerChatSink(ServerPlayer player, String name) {
        this.player = player;
        this.name = name;
    }

    @Override
    public void say(String message) {
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("<" + name + "> ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(message)));
    }

    @Override
    public void warn(String message) {
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("<" + name + "> ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(message).withStyle(ChatFormatting.RED)));
    }
}
