package dev.moma.paper;

import io.netty.channel.*;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Viewer-only metadata: private selection glow and dragon interpolation, without enabling server AI. */
final class PresentationMetadata implements Listener {
    private static final String HANDLER = "mud_private_selection";
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final MomaPlugin plugin;
    private final Metadata metadata;
    private final Map<UUID,Integer> dragons = new HashMap<>();
    private final Set<Integer> dragonIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final class Binding {
        final Player player;
        final Channel channel;
        volatile int selectedId = -1;
        UUID selectedUuid; // Main-thread only; Netty uses the numeric snapshot above.
        boolean green;
        final String teamName;
        Binding(Player player, Channel channel) {
            this.player = player; this.channel = channel;
            teamName="mudg"+player.getUniqueId().toString().replace("-","").substring(0,8);
        }
    }
    PresentationMetadata(MomaPlugin plugin) {
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
                        context.write(selected < 0 && dragonIds.isEmpty() ? packet : metadata.overlay(packet, selected, dragonIds), promise);
                    }
                });
            });
            return binding;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot attach private glow", error); }
    }
    void select(Player player, UUID selected) { select(player,selected,false); }
    void select(Player player, UUID selected, boolean green) {
        Binding binding = bindings.get(player.getUniqueId());
        if (binding == null && selected == null) return;
        if (binding == null) binding = attach(player);
        if (Objects.equals(binding.selectedUuid, selected) && binding.green==green) return;
        UUID previous = binding.selectedUuid;
        Entity entity = selected == null ? null : Bukkit.getEntity(selected);
        try {
            if(binding.green && previous!=null)metadata.sendGreenTeam(player,binding.teamName,previous,false);
            if(green && entity!=null)metadata.sendGreenTeam(player,binding.teamName,selected,true);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot update private selection color",error); }
        binding.selectedUuid = entity == null ? null : selected;
        binding.selectedId = entity == null ? -1 : entity.getEntityId();
        binding.green = entity != null && green;
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
        Integer dragonId=dragons.remove(id);if(dragonId!=null)dragonIds.remove(dragonId);
        for (Binding binding : bindings.values()) if (id.equals(binding.selectedUuid)) select(binding.player, null);
    }
    void spawned(Entity entity) {
        if(entity instanceof EnderDragon){dragons.put(entity.getUniqueId(),entity.getEntityId());dragonIds.add(entity.getEntityId());}
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
        dragons.clear();dragonIds.clear();
    }
    static byte glowing(byte flags) { return (byte) (flags | 0x40); }
    static byte animatedDragon(byte flags) { return (byte)(flags & ~1); }

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
        private final Class<?> teamClass = Class.forName("net.minecraft.world.scores.PlayerTeam");
        private final Constructor<?> scoreboard = Class.forName("net.minecraft.world.scores.Scoreboard").getConstructor();
        private final Constructor<?> team = teamClass.getConstructor(scoreboard.getDeclaringClass(),String.class);
        private final Method setTeamColor = teamClass.getMethod("setColor",Optional.class);
        private final Method teamPlayers = teamClass.getMethod("getPlayers");
        private final Method greenColor = Class.forName("net.minecraft.world.scores.TeamColor").getMethod("byName",String.class);
        private final Class<?> teamPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
        private final Method createTeam = teamPacket.getMethod("createAddOrModifyPacket",teamClass,boolean.class);
        private final Method removeTeam = teamPacket.getMethod("createRemovePacket",teamClass);
        private final Class<?> accessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
        private final Method get = Class.forName("net.minecraft.network.syncher.SynchedEntityData").getMethod("get", accessorClass);
        private final Object flagsAccessor, byteSerializer;
        private final int flagsId, mobFlagsId;
        Metadata() throws ReflectiveOperationException {
            Field flags = Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("DATA_SHARED_FLAGS_ID"); flags.setAccessible(true);
            flagsAccessor = flags.get(null); flagsId = (int) accessorClass.getMethod("id").invoke(flagsAccessor);
            byteSerializer = accessorClass.getMethod("serializer").invoke(flagsAccessor);
            Field mobFlags=Class.forName("net.minecraft.world.entity.Mob").getDeclaredField("DATA_MOB_FLAGS_ID");mobFlags.setAccessible(true);
            mobFlagsId=(int)accessorClass.getMethod("id").invoke(mobFlags.get(null));
        }
        Channel channel(Player player) throws ReflectiveOperationException {
            return (Channel) channel.get(connection.get(listener.get(handle.invoke(player))));
        }
        void sendFlags(Player player, Entity entity) throws ReflectiveOperationException {
            byte flags = (byte) get.invoke(entityData.invoke(handle.invoke(entity)), flagsAccessor);
            Object message = packet.newInstance(entity.getEntityId(), List.of(value.newInstance(flagsId, byteSerializer, flags)));
            send.invoke(listener.get(handle.invoke(player)), message);
        }
        @SuppressWarnings("unchecked")
        void sendGreenTeam(Player player,String name,UUID entity,boolean create) throws ReflectiveOperationException {
            Object coloredTeam=team.newInstance(scoreboard.newInstance(),name);
            Object packet;
            if(create) {
                setTeamColor.invoke(coloredTeam,Optional.of(greenColor.invoke(null,"green")));
                ((Collection<String>)teamPlayers.invoke(coloredTeam)).add(entity.toString());
                packet=createTeam.invoke(null,coloredTeam,true);
            } else packet=removeTeam.invoke(null,coloredTeam);
            send.invoke(listener.get(handle.invoke(player)),packet);
        }
        Object overlay(Object message, int selected, Set<Integer> dragons) throws ReflectiveOperationException {
            if (bundleClass.isInstance(message)) {
                var replaced = new ArrayList<>(); boolean changed = false;
                for (Object child : (Iterable<?>) subPackets.invoke(message)) { Object result = overlay(child, selected, dragons); replaced.add(result); changed |= child != result; }
                return changed ? bundle.newInstance(replaced) : message;
            }
            if (!packetClass.isInstance(message))return message;
            int entityId=(int)packetId.invoke(message);boolean glow=entityId==selected,dragon=dragons.contains(entityId);
            if(!glow && !dragon)return message;
            List<?> original = (List<?>) items.invoke(message);
            ArrayList<Object> replaced = null;
            for (int i = 0; i < original.size(); i++) {
                Object entry = original.get(i);
                int id=(int)valueId.invoke(entry);byte flags;
                if(glow && id==flagsId)flags=glowing((byte)data.invoke(entry));
                else if(dragon && id==mobFlagsId)flags=animatedDragon((byte)data.invoke(entry));
                else continue;
                if (replaced == null) replaced = new ArrayList<>(original);
                replaced.set(i, value.newInstance(id, serializer.invoke(entry), flags));
            }
            return replaced == null ? message : packet.newInstance(entityId, replaced);
        }
    }
}
