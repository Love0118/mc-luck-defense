package dev.moma.paper;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefenseMilestoneIntegrationTest {
    @TempDir Path directory;
    private Player player(World world){var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getLocation()).thenReturn(new Location(world,0,65,0));when(p.getGameMode()).thenReturn(GameMode.ADVENTURE);when(p.teleport(any(Location.class))).thenReturn(true);return p;}
    private void nextRound(GameSession session,int round)throws Exception {
        var elapsed=Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);var rules=CampaignRules.standard();elapsed.setLong(session.campaign,rules.preparationTicks()+(long)(round-1)*rules.roundTicks()-1);
    }
    @Test void actualGameTickPaysAtReachedMilestonesBeforeLeavingAndSpectatorNeverEarns()throws Exception {
        var world=mock(World.class);when(world.getChunkAt(anyInt(),anyInt())).thenReturn(mock(Chunk.class));var owner=player(world);var viewer=player(world);
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var maps=mock(ArenaMaps.class);when(maps.get("a")).thenReturn(new ArenaMap("a",world,0,64,0,new Grid(6)));
        Map<String,Long> paid=new LinkedHashMap<>();
        try(var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class);var entities=mockConstruction(EntityAdapter.class,(entity,c)->{
            when(entity.advanceAll(any(),any())).thenReturn(true);when(entity.spawnEnemy(any(),any(),any(),anyBoolean())).thenAnswer(call->UUID.randomUUID());
        });var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(owner.getUniqueId())).thenReturn(owner);bukkit.when(()->Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
            var games=new GameService(plugin,maps,CampaignRules.standard());games.rewards=new DefenseRewards(directory.resolve("rewards.json"),()->new NoquiHooks(){
                public boolean rewardStarlight(UUID id,String transaction,long amount){assertEquals(owner.getUniqueId(),id);assertNull(paid.put(transaction,amount));return true;}
            },s->{});
            games.join(owner,"a");var session=games.session(owner);games.spectate(viewer,session.sessionId);
            nextRound(session,99);games.tick();assertTrue(paid.isEmpty());assertTrue(games.playing(owner));
            nextRound(session,100);games.tick();assertEquals(10,paid.values().stream().mapToLong(Long::longValue).sum());assertTrue(games.playing(owner));
            nextRound(session,300);games.tick();assertEquals(60,paid.values().stream().mapToLong(Long::longValue).sum());assertEquals(3,paid.size());assertTrue(games.playing(owner));
            games.leave(viewer);assertEquals(3,paid.size());games.leave(owner);games.disconnect(owner);assertEquals(3,paid.size());
        }
    }
}
