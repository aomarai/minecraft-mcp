package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.EventType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Feeds chat, joins, leaves and player deaths into the event buffer. These callbacks run on the game
 * thread, and publishing only appends to a bounded buffer, so they never block it. What players wrote
 * is untrusted; the buffer cleans and shortens it, and nothing here acts on it.
 */
final class FabricEventPublisher {

    private FabricEventPublisher() {
    }

    static void register(EventBuffer events) {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                events.publish(EventType.CHAT, name(sender), message.signedContent()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                events.publish(EventType.JOIN, name(handler.getPlayer()), ""));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                events.publish(EventType.LEAVE, name(handler.getPlayer()), ""));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                events.publish(EventType.DEATH, name(player), source.getLocalizedDeathMessage(player).getString());
            }
        });
    }

    private static String name(ServerPlayer player) {
        return player.getName().getString();
    }
}
