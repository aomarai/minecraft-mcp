package dev.aomarai.minecraftmcp.fabric;

import com.mojang.authlib.GameProfile;
import dev.aomarai.minecraftmcp.core.EventBuffer;
import dev.aomarai.minecraftmcp.core.EventType;
import dev.aomarai.minecraftmcp.core.GameEvent;
import dev.aomarai.minecraftmcp.core.Inventory;
import dev.aomarai.minecraftmcp.core.PlayerInfo;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Runs inside a real server with a fake player that is placed exactly as a connecting client would be
 * (through the player list, over an in-memory channel), so the Fabric callbacks and the adapter readers
 * see what they see in production. Unit tests cannot do this; the Docker smoke test has no players.
 */
public final class FakePlayerGameTest implements FabricGameTest {

    private static final EventBuffer EVENTS = new EventBuffer(100);
    private static boolean registered;

    private static synchronized void register() {
        if (!registered) {
            FabricEventPublisher.register(EVENTS);
            registered = true;
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void aFakePlayerJoinsChatsDiesAndLeavesAndTheAdaptersSeeIt(GameTestHelper helper) {
        register();
        long before = EVENTS.read(-1, 1).latest();
        MinecraftServer server = helper.getLevel().getServer();

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ServerPlayer player = join(server, helper, "Steve", connection);
        helper.assertTrue(types(before).equals(List.of(EventType.JOIN)), "joining is recorded: " + types(before));

        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        var listed = new FabricPlayerReader(server).listPlayers();
        var inventory = new FabricInventoryReader(server).inventoryOf("Steve");

        ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(
                PlayerChatMessage.system("hello\nworld"), player, ChatType.bind(ChatType.CHAT, player));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(listed.isDone() && listed.join().stream().map(PlayerInfo::name).toList().contains("Steve"),
                    "the player reader lists the fake player");
            Inventory read = inventory.join();
            helper.assertTrue(read.stacks().stream().anyMatch(s -> s.item().equals("minecraft:diamond") && s.count() == 3),
                    "the inventory reader sees the diamonds: " + read);

            GameEvent chat = EVENTS.read(before, 10).events().get(1);
            helper.assertTrue(chat.type() == EventType.CHAT && chat.player().equals("Steve")
                    && chat.text().equals("hello world") && chat.untrusted(), "chat is recorded cleaned and untrusted: " + chat);

            player.die(helper.getLevel().damageSources().genericKill());
            helper.assertTrue(types(before).contains(EventType.DEATH), "dying is recorded: " + types(before));

            // As a dropped connection ends: the channel closes and the connection hands over to its listener.
            connection.disconnect(Component.literal("test over"));
            connection.handleDisconnection();
            helper.runAfterDelay(2, () -> {
                List<EventType> all = types(before);
                helper.assertTrue(all.equals(List.of(EventType.JOIN, EventType.CHAT, EventType.DEATH, EventType.LEAVE)),
                        "the whole sequence is recorded in order: " + all);
                helper.succeed();
            });
        });
    }

    private static List<EventType> types(long after) {
        return EVENTS.read(after, 100).events().stream().map(GameEvent::type).toList();
    }

    private static ServerPlayer join(MinecraftServer server, GameTestHelper helper, String name, Connection connection) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }
}
