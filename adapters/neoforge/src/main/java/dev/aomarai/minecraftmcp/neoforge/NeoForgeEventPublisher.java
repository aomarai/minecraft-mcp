package dev.aomarai.minecraftmcp.neoforge;

import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.EventType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Feeds chat, joins, leaves and player deaths into the event buffer. These callbacks run on the game
 * thread, and publishing only appends to a bounded buffer, so they never block it. What players wrote
 * is untrusted; the buffer cleans and shortens it, and nothing here acts on it.
 */
final class NeoForgeEventPublisher {

    private NeoForgeEventPublisher() {
    }

    static void register(EventBuffer events) {
        NeoForge.EVENT_BUS.addListener(ServerChatEvent.class, event ->
                events.publish(EventType.CHAT, name(event.getPlayer()), event.getRawText()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                events.publish(EventType.JOIN, name(player), "");
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                events.publish(EventType.LEAVE, name(player), "");
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                events.publish(EventType.DEATH, name(player),
                        event.getSource().getLocalizedDeathMessage(player).getString());
            }
        });
    }

    private static String name(ServerPlayer player) {
        return player.getName().getString();
    }
}
