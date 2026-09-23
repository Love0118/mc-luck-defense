package dev.moma.paper;

import dev.moma.core.*;
import dev.moma.runtime.StateCodec;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReserveLifecycleTest {
    @Test void visibleReserveSurvivesReloadWithSelectionAndIsRemovedWithTheSession()throws Exception {
        MomaPlugin plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        World world=mock(World.class);UUID worldId=UUID.randomUUID();when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        Map<String,Chunk> chunks=new HashMap<>();
        when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call->chunks.computeIfAbsent(call.getArgument(0)+","+call.getArgument(1),key->mock(Chunk.class)));
        ArenaMaps maps=mock(ArenaMaps.class);ArenaMap map=new ArenaMap("a",world,0,64,0,new Grid(6));when(maps.get("a")).thenReturn(map);
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getLocation()).thenReturn(map.entrance());
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);when(player.teleport(any(Location.class))).thenReturn(true);
        Map<UUID,Entity> spawned=new HashMap<>();
        try(var bukkit=mockStatic(Bukkit.class);var tools=mockConstruction(SessionTools.class);var appearance=mockConstruction(SpectatorAppearance.class);
            var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->{
                when(adapter.managed(any())).thenReturn(true);when(adapter.moveDefender(any(),any())).thenReturn(true);
                var spawn=(org.mockito.stubbing.Answer<UUID>)call->{
                    UUID id=UUID.randomUUID();Entity entity=mock(Entity.class);when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
                    spawned.put(id,entity);return id;
                };
                when(adapter.spawnDefender(any(),any(),any(),any(),any())).thenAnswer(spawn);
                when(adapter.spawnReserve(any(),any(),any(),any(),anyInt())).thenAnswer(spawn);
            })) {
            bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            bukkit.when(()->Bukkit.getEntity(any())).thenAnswer(call->spawned.get(call.getArgument(0)));
            GameService original=new GameService(plugin,maps,CampaignRules.standard());original.join(player,"a");GameSession session=original.session(player);
            session.arena.credit(1000);session.arena.toggleMerging(player.getUniqueId());for(int i=0;i<37;i++)original.summon(player);
            Defender reserve=session.arena.reserveUnits().getFirst();original.select(player,reserve.entityId());
            SessionState.Game state=StateCodec.read(StateCodec.write(original.saveState()),SessionState.Game.class);
            GameService restored=new GameService(plugin,maps,CampaignRules.standard());restored.restoreState(state);restored.rebindPresentation();
            assertEquals(StateCodec.fingerprint(original.saveState()),StateCodec.fingerprint(restored.saveState()));
            assertEquals(reserve.entityId(),restored.session(player).arena.selected().orElseThrow().entityId());
            verify(restored.entities).selectGlow(player,reserve.entityId());assertTrue(chunks.containsKey("0,-3"));
            restored.leave(player);
            for(UUID id:spawned.keySet())verify(restored.entities).remove(id);
            for(Chunk chunk:chunks.values())verify(chunk).removePluginChunkTicket(plugin);
            assertFalse(restored.hasSessions());
        }
    }
}
