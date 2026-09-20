package dev.moma.paper;

import org.bukkit.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundLeaderboardTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void lostDisplayIsRecreatedWithCurrentRecordsAndTicketReleased() throws Exception {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);
        when(world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);when(world.getChunkAt(any(Location.class))).thenReturn(chunk);when(world.getEntities()).thenReturn(java.util.List.of());
        var first=mock(org.bukkit.entity.TextDisplay.class);var replacement=mock(org.bukkit.entity.TextDisplay.class);
        boolean[] valid={true};when(first.isValid()).thenAnswer(c->valid[0]);when(replacement.isValid()).thenReturn(true);
        int[] spawns={0};
        when(world.spawn(any(Location.class),eq(org.bukkit.entity.TextDisplay.class),any(java.util.function.Consumer.class)))
                .thenAnswer(c->{var entity=spawns[0]++==0?first:replacement;((java.util.function.Consumer<org.bukkit.entity.TextDisplay>)c.getArgument(2)).accept(entity);return entity;});
        var plugin=mock(MomaPlugin.class);when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);var task=mock(org.bukkit.scheduler.BukkitTask.class);
        var action=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(100L),eq(100L))).thenReturn(task);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            RoundLeaderboard board=new RoundLeaderboard(plugin,new Lobby(new Location(world,0,64,0),256));
            verify(chunk).addPluginChunkTicket(plugin);
            var player=mock(org.bukkit.entity.Player.class);when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());when(player.getName()).thenReturn("Ranker");
            board.record(player,88);
            verify(scheduler).runTaskTimer(eq(plugin),action.capture(),eq(100L),eq(100L));
            action.getValue().run();assertEquals(1,spawns[0]);
            valid[0]=false;action.getValue().run();assertEquals(2,spawns[0]);
            verify(replacement).text(argThat(text->text.toString().contains("Ranker")&&text.toString().contains("88")));
            action.getValue().run();assertEquals(2,spawns[0]);
            board.close();board.close();verify(task).cancel();verify(replacement).remove();verify(chunk).removePluginChunkTicket(plugin);
            assertEquals(88,dev.moma.core.RoundRecords.load(directory.resolve("round-records.properties")).top(1).getFirst().round());
        }
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
