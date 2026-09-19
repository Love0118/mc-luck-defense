package dev.moma.paper;

import io.netty.channel.*;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Paper 26.3 packet adapter. Only one viewer's metadata copies get the glow bit. */
final class PrivateGlow implements Listener {
    private static final String HANDLER = "mud_private_selection";
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final MomaPlugin plugin;
    private final Metadata metadata;
    private static final class Binding {
        final Player player;
        final Channel channel;
        volatile int selectedId = -1;
        UUID selectedUuid; // Main-thread only; Netty uses the numeric snapshot above.
        Binding(Player player, Channel channel) { this.player = player; this.channel = channel; }
    }
    PrivateGlow(MomaPlugin plugin) {
        this.plugin = plugin;
        try { metadata = new Metadata(); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Private selection glow requires the pinned Paper 26.3 mappings", error); }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) attach(player);
    }
    private Binding attach(Player player) {
        Binding existing = bindings.get(player.getUniqueId());
        if (existing != null) return existing;
        try {
            var binding = new Binding(player, metadata.channel(player));
            bindings.put(player.getUniqueId(), binding);
            binding.channel.eventLoop().execute(() -> {
                if (binding.channel.pipeline().get(HANDLER) != null) binding.channel.pipeline().remove(HANDLER);
                binding.channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                        int selected = binding.selectedId;
                        context.write(selected < 0 ? packet : metadata.overlay(packet, selected), promise);
                    }
                });
            });
            return binding;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot attach private glow", error); }
    }
    void select(Player player, UUID selected) {
        Binding binding = bindings.get(player.getUniqueId());
        if (binding == null && selected == null) return;
        if (binding == null) binding = attach(player);
        if (Objects.equals(binding.selectedUuid, selected)) return;
        UUID previous = binding.selectedUuid;
        Entity entity = selected == null ? null : Bukkit.getEntity(selected);
        binding.selectedUuid = entity == null ? null : selected;
        binding.selectedId = entity == null ? -1 : entity.getEntityId();
        refresh(player, previous);
        refresh(player, binding.selectedUuid);
    }
    private void refresh(Player player, UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        if (entity == null || !entity.isValid() || !entity.getWorld().equals(player.getWorld())) return;
        try { metadata.sendFlags(player, entity); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot update private glow", error); }
    }
    void removed(UUID id) {
        for (Binding binding : bindings.values()) if (id.equals(binding.selectedUuid)) select(binding.player, null);
    }
    @EventHandler public void join(PlayerJoinEvent event) { attach(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void track(PlayerTrackEntityEvent event) {
        Binding binding = bindings.get(event.getPlayer().getUniqueId());
        UUID entity = event.getEntity().getUniqueId();
        if (binding == null || !entity.equals(binding.selectedUuid)) return;
        // Initial metadata may omit default flags. Refresh after the spawn packet is sent.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (binding.player.isOnline() && entity.equals(binding.selectedUuid)) refresh(binding.player, entity);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { detach(event.getPlayer().getUniqueId()); }
    private void detach(UUID id) {
        Binding binding = bindings.remove(id); if (binding == null) return;
        binding.selectedId = -1; binding.selectedUuid = null;
        binding.channel.eventLoop().execute(() -> {
            if (binding.channel.pipeline().get(HANDLER) != null) binding.channel.pipeline().remove(HANDLER);
        });
    }
    void close() {
        for (UUID id : List.copyOf(bindings.keySet())) {
            Binding binding = bindings.get(id); select(binding.player, null); detach(id);
        }
        HandlerList.unregisterAll(this);
    }
    static byte glowing(byte flags) { return (byte) (flags | 0x40); }

    private static final class Metadata {
        private final Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        private final Class<?> valueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
        private final Class<?> bundleClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
        private final Constructor<?> packet = packetClass.getConstructor(int.class, List.class);
        private final Constructor<?> value = valueClass.getConstructor(int.class, Class.forName("net.minecraft.network.syncher.EntityDataSerializer"), Object.class);
        private final Constructor<?> bundle = bundleClass.getConstructor(Iterable.class);
        private final Method packetId = packetClass.getMethod("id"), items = packetClass.getMethod("packedItems");
        private final Method valueId = valueClass.getMethod("id"), serializer = valueClass.getMethod("serializer"), data = valueClass.getMethod("value");
        private final Method subPackets = bundleClass.getMethod("subPackets");
        private final Method handle = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("getHandle");
        private final Field listener = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
        private final Field connection = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl").getField("connection");
        private final Field channel = Class.forName("net.minecraft.network.Connection").getField("channel");
        private final Method send = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl").getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        private final Method entityData = Class.forName("net.minecraft.world.entity.Entity").getMethod("getEntityData");
        private final Class<?> accessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
        private final Method get = Class.forName("net.minecraft.network.syncher.SynchedEntityData").getMethod("get", accessorClass);
        private final Object flagsAccessor, byteSerializer;
        private final int flagsId;
        Metadata() throws ReflectiveOperationException {
            Field flags = Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("DATA_SHARED_FLAGS_ID"); flags.setAccessible(true);
            flagsAccessor = flags.get(null); flagsId = (int) accessorClass.getMethod("id").invoke(flagsAccessor);
            byteSerializer = accessorClass.getMethod("serializer").invoke(flagsAccessor);
        }
        Channel channel(Player player) throws ReflectiveOperationException {
            return (Channel) channel.get(connection.get(listener.get(handle.invoke(player))));
        }
        void sendFlags(Player player, Entity entity) throws ReflectiveOperationException {
            byte flags = (byte) get.invoke(entityData.invoke(handle.invoke(entity)), flagsAccessor);
            Object message = packet.newInstance(entity.getEntityId(), List.of(value.newInstance(flagsId, byteSerializer, flags)));
            send.invoke(listener.get(handle.invoke(player)), message);
        }
        Object overlay(Object message, int selected) throws ReflectiveOperationException {
            if (bundleClass.isInstance(message)) {
                var replaced = new ArrayList<>(); boolean changed = false;
                for (Object child : (Iterable<?>) subPackets.invoke(message)) { Object result = overlay(child, selected); replaced.add(result); changed |= child != result; }
                return changed ? bundle.newInstance(replaced) : message;
            }
            if (!packetClass.isInstance(message) || (int) packetId.invoke(message) != selected) return message;
            List<?> original = (List<?>) items.invoke(message);
            ArrayList<Object> replaced = null;
            for (int i = 0; i < original.size(); i++) {
                Object entry = original.get(i);
                if ((int) valueId.invoke(entry) != flagsId) continue;
                if (replaced == null) replaced = new ArrayList<>(original);
                replaced.set(i, value.newInstance(flagsId, serializer.invoke(entry), glowing((byte) data.invoke(entry))));
            }
            return replaced == null ? message : packet.newInstance(selected, replaced);
        }
    }
}
