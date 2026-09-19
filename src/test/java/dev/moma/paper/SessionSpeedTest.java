package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionSpeedTest {
    private org.mockito.MockedConstruction<SessionTools> tools;
    @BeforeEach void mockTools() { tools=mockConstruction(SessionTools.class); }
    @AfterEach void closeTools() { tools.close(); }
    private Player player(World world) {
        Player player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getY()).thenReturn(65d);
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(player.teleport(any(Location.class))).thenReturn(true);
        return player;
    }
    private GameService games(World world) {
        MomaPlugin plugin=mock(MomaPlugin.class); ArenaMaps maps=mock(ArenaMaps.class);
        when(plugin.namespace()).thenReturn("momadefense");
        for(String id:List.of("a","b")) when(maps.get(id)).thenReturn(new ArenaMap(id,world,0,64,0,new Grid(6)));
        when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call->mock(Chunk.class));
        return new GameService(plugin,maps,CampaignRules.standard());
    }
    @Test void speedsAreIndependentBoundedAndDoNotResetClockOrCooldownOnChange() {
        World world=mock(World.class);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->when(adapter.advanceAll(any(),any())).thenReturn(true));
            var bukkit=mockStatic(Bukkit.class)) {
            GameService games=games(world); Player first=player(world),second=player(world),viewer=player(world);
            bukkit.when(()->Bukkit.getPlayer(first.getUniqueId())).thenReturn(first);
            bukkit.when(()->Bukkit.getPlayer(second.getUniqueId())).thenReturn(second);
            games.join(first,"a"); games.join(second,"b"); games.speed(second,8);
            for(int i=0;i<10;i++) games.tick();
            GameSession a=games.session(first),b=games.session(second);
            assertEquals(10,a.simulationTick); assertEquals(80,b.simulationTick);
            assertEquals(9,a.campaign.elapsed()); assertEquals(79,b.campaign.elapsed());
            games.speed(second,2); games.tick();
            assertEquals(11,a.simulationTick); assertEquals(82,b.simulationTick);
            for(int invalid:new int[]{-1,0,3,9,16,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->games.speed(second,invalid));
            assertEquals(2,b.speed()); assertEquals(82,b.simulationTick);
            games.spectate(viewer,b.sessionId);
            assertThrows(IllegalArgumentException.class,()->games.speed(viewer,8)); assertEquals(2,b.speed());
            games.leave(first); games.join(first,"a");
            assertEquals(1,games.session(first).speed()); assertEquals(0,games.session(first).simulationTick);
        }
    }
    private List<Object> runCombat(int[] frameSpeeds) {
        World world=mock(World.class);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->{
                long[] nextId={1000};
                when(adapter.spawnEnemy(any(),any(),any(),anyBoolean())).thenAnswer(call->new UUID(0,nextId[0]++));
                when(adapter.advanceAll(any(),any())).thenReturn(true);
                when(adapter.moveDefender(any(),any())).thenReturn(true);
            }); var bukkit=mockStatic(Bukkit.class)) {
            GameService games=games(world); Player player=player(world);
            bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            games.join(player,"a"); GameSession session=games.session(player);
            session.arena.credit(1000);
            for(UnitType type:UnitType.values()) session.arena.summon(player.getUniqueId(),new SummonRoll(type,Rarity.LEGENDARY),
                    (t,r,c)->new UUID(0,t.ordinal()+1));
            var durable=new Enemy(new UUID(0,999),"a",EnemyType.ZOMBIE,1e10,6,0,true);
            durable.slow(.5,40); session.arena.addEnemy(durable);
            for(int speed:frameSpeeds) { games.speed(player,speed); games.tick(); }
            assertTrue(games.playing(player)); assertTrue(session.arena.coins()>860,"Actual kill rewards must occur");
            assertTrue(session.arena.defenders().stream().anyMatch(d->d.nextAttackTick()>0));
            List<Object> state=new ArrayList<>();
            state.add(session.simulationTick); state.add(session.campaign.elapsed()); state.add(session.campaign.round());
            state.add(session.arena.coins()); state.add(session.arena.outcome());
            for(Enemy enemy:session.arena.activeEnemies()) state.add(List.of(enemy.entityId(),enemy.health(),enemy.progress(),enemy.slowAt(session.simulationTick)));
            for(Defender defender:session.arena.activeDefenders()) state.add(List.of(defender.type(),defender.nextAttackTick(),defender.consecutiveHits()));
            verify(adapters.constructed().getFirst(),times(frameSpeeds.length)).advanceAll(session.arena,session.map);
            return state;
        }
    }
    @Test void acceleratedAndChangingSpeedsMatchEveryCombatAndWaveStepAtEqualGameTime() {
        int[] normal=new int[800]; Arrays.fill(normal,1);
        int[] fast=new int[100]; Arrays.fill(fast,8);
        int[] changed=new int[275];
        Arrays.fill(changed,0,100,4); Arrays.fill(changed,100,125,8); Arrays.fill(changed,125,175,2); Arrays.fill(changed,175,275,1);
        assertEquals(runCombat(normal),runCombat(fast));
        assertEquals(runCombat(normal),runCombat(changed));
    }
    @Test void defeatDuringAnAcceleratedFrameStopsTheRemainingStepsAndReturnsOnce() {
        World world=mock(World.class);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->{
            when(adapter.advanceAll(any(),any())).thenReturn(true);
            when(adapter.spawnEnemy(any(),any(),any(),anyBoolean())).thenReturn(UUID.randomUUID());
        });var bukkit=mockStatic(Bukkit.class)) {
            GameService games=games(world);Player player=player(world);
            bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            games.join(player,"a");GameSession session=games.session(player);
            for(int i=0;i<99;i++)session.arena.addEnemy(new Enemy(UUID.randomUUID(),"a",EnemyType.ZOMBIE,1e10,1,0,false));
            for(int i=0;i<300;i++)games.tick();
            games.speed(player,8);games.tick();games.tick();
            assertEquals(Arena.Outcome.ENEMY_LIMIT,session.arena.outcome());
            assertEquals(301,session.simulationTick);assertEquals(300,session.campaign.elapsed());
            assertFalse(games.playing(player));verify(player,times(2)).teleport(any(Location.class));
        }
    }
}
