package dev.moma.chat.smoke;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.LastSeenTrackedEntry;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.bk.oharass.freedomchat.FreedomHandler;
import sun.misc.Unsafe;

/** Runs only on the isolated fixture; never installed on the public server. */
public final class GuardSmoke extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                verify();
                Files.writeString(Path.of("guard-smoke.txt"), "PASS: 14 signed/unsigned chat variants; formatting; system passthrough; status; selective ack cleanup; commands preserved\n");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "Guard smoke failed", failure);
                try { Files.writeString(Path.of("guard-smoke.txt"), "FAIL: " + failure + "\n"); }
                catch (Exception ignored) { }
            } finally { Bukkit.shutdown(); }
        });
    }

    private void verify() throws Exception {
        var registry = MinecraftServer.getServer().registryAccess();
        var codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registry)).codec();
        var handler = new FreedomHandler(getLogger(), true, false, true, false);
        var channel = new EmbeddedChannel(handler);
        try {
            for (var type : List.of(ChatType.CHAT, ChatType.SAY_COMMAND, ChatType.MSG_COMMAND_INCOMING,
                    ChatType.MSG_COMMAND_OUTGOING, ChatType.TEAM_MSG_COMMAND_INCOMING,
                    ChatType.TEAM_MSG_COMMAND_OUTGOING, ChatType.EMOTE_COMMAND)) {
                var bound = ChatType.bind(type, registry, Component.literal("Sender")).withTargetName(Component.literal("Target"));
                for (boolean signed : List.of(false, true)) {
                    var content = Component.literal("formatted message");
                    var signature = signed ? Optional.of(signature(1)) : Optional.<MessageSignature>empty();
                    var input = new ClientboundPlayerChatPacket(0, UUID.randomUUID(), 0, signature,
                            new SignedMessageBody.Packed("original message", Instant.now(), 0, LastSeenMessages.Packed.EMPTY),
                            Optional.of(content), FilterMask.PASS_THROUGH, bound);
                    require(channel.writeOutbound(input), "No rewritten bytes");
                    ByteBuf wire = channel.readOutbound();
                    try {
                        ByteBuf expectedWire = io.netty.buffer.Unpooled.buffer();
                        try {
                            codec.encode(expectedWire, new ClientboundSystemChatPacket(bound.decorate(content), false));
                            require(io.netty.buffer.ByteBufUtil.equals(wire, expectedWire), "Chat style/decoration changed: " + type);
                        } finally { expectedWire.release(); }
                        var packet = codec.decode(wire);
                        require(packet instanceof ClientboundSystemChatPacket, "Reportable player-chat packet survived: " + type);
                        var system = (ClientboundSystemChatPacket) packet;
                        require(system.content().getString().equals(bound.decorate(content).getString()),
                                "Chat decoration changed: " + type + " expected=" + bound.decorate(content) + " actual=" + system.content());
                        require(!system.overlay() && !wire.isReadable(), "Incorrect system-chat encoding");
                    } finally { wire.release(); }
                }
            }
            var system = new ClientboundSystemChatPacket(Component.literal("existing system message"), false);
            channel.writeOutbound(system);
            require(channel.readOutbound() == system, "Existing system packet changed");
            var status = new ServerStatus(Component.literal("Fixture"), Optional.empty(), Optional.empty(), Optional.empty(), false);
            channel.writeOutbound(new ClientboundStatusResponsePacket(status));
            ByteBuf wire = channel.readOutbound();
            try {
                var buf = new FriendlyByteBuf(wire);
                require(buf.readVarInt() == 0, "Status packet id changed");
                var json = com.google.gson.JsonParser.parseString(buf.readUtf()).getAsJsonObject();
                require(json.get("preventsChatReports").getAsBoolean(), "NCR advertisement missing");
                require(!json.has("enforcesSecureChat") || !json.get("enforcesSecureChat").getAsBoolean(), "Secure chat claim changed");
            } finally { wire.release(); }
            require(Bukkit.getCommandMap().getCommand("minecraft:me") != null, "/me command removed");
        } finally { channel.finishAndReleaseAll(); }
        verifyAcknowledgements(handler);
    }

    private void verifyAcknowledgements(FreedomHandler handler) throws Exception {
        // A synthetic listener avoids creating authenticated players on the fixture.
        // It is used only for its validator field and never receives packets/callbacks.
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var unsafe = (Unsafe) unsafeField.get(null);
        var listener = (ServerGamePacketListenerImpl) unsafe.allocateInstance(ServerGamePacketListenerImpl.class);
        var validator = new LastSeenMessagesValidator(20);
        set(listener, ServerGamePacketListenerImpl.class, "lastSeenMessages", validator);
        var first = signature(3); var removed = signature(4);
        validator.addPending(first); validator.addPending(removed);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        set(connection, Connection.class, "packetListener", listener);
        var channel = new EmbeddedChannel();
        // Add the Connection after activation to avoid its normal handshake bootstrap.
        channel.pipeline().addLast("packet_handler", connection);
        channel.pipeline().addLast("freedom_handler", handler);
        try {
            var bound = ChatType.bind(ChatType.EMOTE_COMMAND, MinecraftServer.getServer().registryAccess(), Component.literal("Sender"));
            channel.writeOutbound(new ClientboundPlayerChatPacket(0, UUID.randomUUID(), 0, Optional.of(removed),
                    new SignedMessageBody.Packed("emote", Instant.now(), 0, LastSeenMessages.Packed.EMPTY),
                    Optional.empty(), FilterMask.PASS_THROUGH, bound));
            ByteBuf wire = channel.readOutbound(); wire.release();
            var field = LastSeenMessagesValidator.class.getDeclaredField("trackedMessages");field.setAccessible(true);
            @SuppressWarnings("unchecked") var entries = (List<LastSeenTrackedEntry>) field.get(validator);
            require(entries.size() == 21, "Padding or unrelated acknowledgements removed");
            require(entries.subList(0,20).stream().allMatch(java.util.Objects::isNull), "Validator padding changed");
            require(entries.get(20).signature().equals(first) && entries.get(20).pending(), "Unrelated signature changed");
        } finally {
            channel.pipeline().remove("packet_handler");
            channel.finishAndReleaseAll();
        }
    }

    private static MessageSignature signature(int seed) {
        byte[] bytes = new byte[MessageSignature.BYTES];java.util.Arrays.fill(bytes, (byte)seed);return new MessageSignature(bytes);
    }
    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);field.setAccessible(true);field.set(owner, value);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
