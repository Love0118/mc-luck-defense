package dev.moma.paper;

import org.bukkit.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundLeaderboardTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void privatePagesAreIndependentClampedAndRecreatedWithCurrentRecords() throws Exception {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);when(world.getChunkAt(any(Location.class))).thenReturn(chunk);when(world.getEntities()).thenReturn(java.util.List.of());
        var texts=new java.util.ArrayList<org.bukkit.entity.TextDisplay>();
        var buttons=new java.util.ArrayList<org.bukkit.entity.Interaction>();
        var contents=new java.util.IdentityHashMap<org.bukkit.entity.TextDisplay,net.kyori.adventure.text.Component>();
        when(world.spawn(any(Location.class),eq(org.bukkit.entity.TextDisplay.class),any(java.util.function.Consumer.class)))
                .thenAnswer(c->{
                    var entity=mock(org.bukkit.entity.TextDisplay.class);when(entity.isValid()).thenReturn(true);
                    doAnswer(call->{contents.put(entity,call.getArgument(0));return null;}).when(entity).text(any());
                    ((java.util.function.Consumer<org.bukkit.entity.TextDisplay>)c.getArgument(2)).accept(entity);texts.add(entity);return entity;
                });
        when(world.spawn(any(Location.class),eq(org.bukkit.entity.Interaction.class),any(java.util.function.Consumer.class)))
                .thenAnswer(c->{
                    var entity=mock(org.bukkit.entity.Interaction.class);when(entity.isValid()).thenReturn(true);
                    when(entity.getUniqueId()).thenReturn(java.util.UUID.randomUUID());when(entity.getLocation()).thenReturn(c.getArgument(0));
                    ((java.util.function.Consumer<org.bukkit.entity.Interaction>)c.getArgument(2)).accept(entity);buttons.add(entity);return entity;
                });
        var plugin=mock(MomaPlugin.class);when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);var task=mock(org.bukkit.scheduler.BukkitTask.class);
        var action=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(20L),eq(20L))).thenReturn(task);
        var a=mock(org.bukkit.entity.Player.class);var b=mock(org.bukkit.entity.Player.class);
        for(var player:java.util.List.of(a,b)) {
            when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world,0,65,3));
        }
        var seed=new dev.moma.core.RoundRecords();
        for(int i=0;i<25;i++)seed.record(new java.util.UUID(0,i),"Ranker"+i,100-i);
        dev.moma.core.RoundRecords.save(directory.resolve("round-records.properties"),seed.snapshot());
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(a,b));
            RoundLeaderboard board=new RoundLeaderboard(plugin,new Lobby(new Location(world,0,64,0),256));
            verify(chunk).addPluginChunkTicket(plugin);
            var first=texts.get(2);var second=texts.get(3);
            verify(first).setVisibleByDefault(false);verify(second).setVisibleByDefault(false);
            verify(a).showEntity(plugin,first);verify(a,never()).showEntity(plugin,second);
            verify(b).showEntity(plugin,second);verify(b,never()).showEntity(plugin,first);
            click(board,a,buttons.get(1),org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            assertTrue(plain(contents.get(first)).contains("[1/3]"));
            click(board,a,buttons.get(1),org.bukkit.inventory.EquipmentSlot.HAND);
            assertTrue(plain(contents.get(first)).contains("[2/3]"));
            assertTrue(plain(contents.get(first)).contains("11. Ranker10"));
            assertTrue(plain(contents.get(second)).contains("[1/3]"));
            click(board,a,buttons.get(1),org.bukkit.inventory.EquipmentSlot.HAND);
            assertTrue(plain(contents.get(first)).contains("[2/3]"));
            verify(scheduler).runTaskTimer(eq(plugin),action.capture(),eq(20L),eq(20L));
            when(first.isValid()).thenReturn(false);action.getValue().run();
            var replacement=texts.get(4);assertTrue(plain(contents.get(replacement)).contains("[2/3]"));
            when(a.getName()).thenReturn("NewLeader");board.record(a,999);
            assertTrue(plain(contents.get(second)).contains("NewLeader"));
            var views=RoundLeaderboard.class.getDeclaredField("views");views.setAccessible(true);
            Object state=((java.util.Map<?,?>)views.get(board)).get(a.getUniqueId());
            var lastClick=state.getClass().getDeclaredField("lastClick");lastClick.setAccessible(true);
            lastClick.setLong(state,Long.MIN_VALUE);click(board,a,buttons.get(1),org.bukkit.inventory.EquipmentSlot.HAND);
            assertTrue(plain(contents.get(replacement)).contains("[3/3]"));
            lastClick.setLong(state,Long.MIN_VALUE);click(board,a,buttons.get(1),org.bukkit.inventory.EquipmentSlot.HAND);
            assertTrue(plain(contents.get(replacement)).contains("[3/3]"));
            lastClick.setLong(state,Long.MIN_VALUE);click(board,a,buttons.get(0),org.bukkit.inventory.EquipmentSlot.HAND);
            assertTrue(plain(contents.get(replacement)).contains("[2/3]"));
            when(a.getWorld()).thenReturn(mock(World.class));action.getValue().run();verify(replacement).remove();
            when(a.getWorld()).thenReturn(world);action.getValue().run();
            assertTrue(plain(contents.get(texts.get(5))).contains("[1/3]"));
            board.close();board.close();verify(task).cancel();verify(second).remove();verify(chunk).removePluginChunkTicket(plugin);
            assertEquals(999,dev.moma.core.RoundRecords.load(directory.resolve("round-records.properties")).top(1).getFirst().round());
        }
    }
    private static void click(RoundLeaderboard board,org.bukkit.entity.Player player,org.bukkit.entity.Entity entity,org.bukkit.inventory.EquipmentSlot hand) {
        var event=new org.bukkit.event.player.PlayerInteractEntityEvent(player,entity,hand);event.setCancelled(true);
        board.interact(event);assertTrue(event.isCancelled());
    }
    private static String plain(net.kyori.adventure.text.Component text) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
    }
    @Test void boardIsFiveBlocksAheadIgnoringSpawnPitch() {
        World world=mock(World.class);
        for(float yaw:new float[]{0,90,180,-90}) {
            Location spawn=new Location(world,20,65,-10,yaw,50);
            Location result=RoundLeaderboard.location(spawn);
            var delta=result.toVector().subtract(spawn.toVector());
            assertEquals(2.8,delta.getY(),1e-9);delta.setY(0);
            assertEquals(5,delta.length(),1e-9);
            Location horizontal=spawn.clone();horizontal.setPitch(0);
            assertEquals(1,delta.normalize().dot(horizontal.getDirection()),1e-9);
            assertEquals(65,spawn.getY());
        }
    }
}
