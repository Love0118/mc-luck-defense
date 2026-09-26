package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionSpeedTest {
    private org.mockito.MockedConstruction<SpectatorAppearance> appearance;
    private org.mockito.MockedConstruction<SessionTools> tools;
    @BeforeEach void mockTools() { tools=mockConstruction(SessionTools.class); appearance=mockConstruction(SpectatorAppearance.class); }
    @AfterEach void closeTools() { tools.close(); appearance.close(); }
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
            for(int invalid:new int[]{-1,0,3,9,64,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->games.speed(second,invalid));
            assertEquals(2,b.speed()); assertEquals(82,b.simulationTick);
            games.spectate(viewer,b.sessionId);
            assertThrows(IllegalArgumentException.class,()->games.speed(viewer,8)); assertEquals(2,b.speed());
            games.leave(first); games.join(first,"a");
            assertEquals(1,games.session(first).speed()); assertEquals(0,games.session(first).simulationTick);
        }
    }
    private List<Object> runCombat(int[] frameSpeeds,boolean boosted) {
        return runCombat(frameSpeeds,boosted,false);
    }
    private List<Object> runCombat(int[] frameSpeeds,boolean boosted,boolean income) {
        World world=mock(World.class);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->{
                long[] nextId={1000};
                when(adapter.spawnEnemy(any(),any(),any(),anyBoolean())).thenAnswer(call->new UUID(0,nextId[0]++));
                when(adapter.advanceAll(any(),any())).thenReturn(true);
                when(adapter.moveDefender(any(),any())).thenReturn(true);
            }); var bukkit=mockStatic(Bukkit.class)) {
            GameService games=games(world); Player player=player(world);
            bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            if(boosted || income) {
                var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
                AchievementStats.reached(data,250);
                AchievementStats.add(data,AchievementCatalog.Metric.MYTHIC,1000);
                AchievementStats.add(data,AchievementCatalog.Metric.GOLD_SPENT,10000000);
                var ids=new ArrayList<String>();if(boosted)ids.add("mythic_1000");if(income)ids.add("gold_spent_10000000");
                TraitSelections.save(data,ids);
            }
            games.join(player,"a"); GameSession session=games.session(player);
            assertEquals(boosted?8:0,session.arena.traits().value(TraitCatalog.Family.SPEED));
            assertEquals(income?TraitCatalog.find("gold_spent_10000000").value():0,session.arena.traits().value(TraitCatalog.Family.GOLD_INCOME));
            when(player.getLocation()).thenReturn(new Location(world,21.99,90,10,135,-30));
            clearInvocations(player);
            session.arena.credit(1000);
            for(UnitType type:UnitType.values()) session.arena.summon(player.getUniqueId(),new SummonRoll(type,Rarity.LEGENDARY),
                    (t,r,c)->new UUID(0,t.ordinal()+1));
            var durable=new Enemy(new UUID(0,999),"a",EnemyType.ZOMBIE,1e10,6,0,true);
            durable.slow(.5,40); session.arena.addEnemy(durable);
            for(int speed:frameSpeeds) { games.speed(player,speed); games.tick(); }
            assertTrue(games.playing(player)); assertTrue(session.arena.earnedCoins()>0,"Actual kill rewards must occur");
            verify(player,never()).teleport(any(Location.class));
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
        int[] normal=new int[1600]; Arrays.fill(normal,1);
        int[] fast=new int[200]; Arrays.fill(fast,8);
        int[] changed=new int[300];
        Arrays.fill(changed,0,50,16);Arrays.fill(changed,50,100,8);Arrays.fill(changed,100,150,4);
        Arrays.fill(changed,150,200,2);Arrays.fill(changed,200,300,1);
        int[] sixteen=new int[100];Arrays.fill(sixteen,16);
        int[] thirtyTwo=new int[50];Arrays.fill(thirtyTwo,32);
        for(boolean boosted:new boolean[]{false,true}) {
            var expected=runCombat(normal,boosted);
            assertEquals(expected,runCombat(fast,boosted));
            assertEquals(expected,runCombat(sixteen,boosted));
            assertEquals(expected,runCombat(thirtyTwo,boosted));
            assertEquals(expected,runCombat(changed,boosted));
        }
    }
    @Test void fractionalIncomeMatchesAtOneAndSixteenSpeedWithAttackSpeedBonus() {
        int[] normal=new int[1600];Arrays.fill(normal,1);
        int[] fast=new int[100];Arrays.fill(fast,16);
        assertEquals(runCombat(normal,true,true),runCombat(fast,true,true));
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
    private List<Object> purchasesAtSpeed(int speed,int gate)throws Exception {
        World world=mock(World.class);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->{
            long[] ids={1000};when(adapter.spawnDefender(any(),any(),any(),any(),any())).thenAnswer(call->new UUID(0,ids[0]++));
            when(adapter.spawnEnemy(any(),any(),any(),anyBoolean())).thenAnswer(call->new UUID(0,ids[0]++));
            when(adapter.advanceAll(any(),any())).thenReturn(true);when(adapter.moveDefender(any(),any())).thenReturn(true);
        });var bukkit=mockStatic(Bukkit.class);var rolls=mockStatic(SummonRoll.class)) {
            GameService games=games(world);Player p=player(world);bukkit.when(()->Bukkit.getPlayer(p.getUniqueId())).thenReturn(p);
            games.join(p,"a");GameSession session=games.session(p);
            var elapsed=Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);
            elapsed.setLong(session.campaign,CampaignRules.standard().preparationTicks()+(gate-1L)*CampaignRules.standard().roundTicks()-2);
            rolls.when(()->SummonRoll.draw(any(),eq(session.arena))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.EPIC));
            session.arena.credit(100_000_000);session.bulkBuying=true;session.autoPlacement=true;
            games.speed(p,speed);for(int i=0;i<64/speed;i++)games.tick();
            if(session.layoutTask!=null) {
                session.layoutTask.result().get(5,java.util.concurrent.TimeUnit.SECONDS);
                games.applyPreparedPlacement(p,session);
            }
            games.preparePlacement(session);
            if(session.layoutTask!=null) {
                session.layoutTask.result().get(5,java.util.concurrent.TimeUnit.SECONDS);
                games.applyPreparedPlacement(p,session);
            }
            assertFalse(session.layoutDirty);
            var layout=new AutoPlacement(session.map.grid()).arrange(session.arena.units());
            // Equal-score cells can differ because completed layouts retain existing positions.
            for(Defender d:session.arena.units())assertEquals(layout.get(d.entityId()),d.cell());
            var state=new ArrayList<Object>();state.add(session.arena.coins());state.add(session.bulkPurchases);state.add(session.arena.spentGold());
            state.add(session.campaign.round());state.add(session.arena.summonTier());state.add(session.simulationTick);
            for(Defender d:session.arena.units())state.add(List.of(d.type(),d.rarity(),d.enhancement(),d.deployed(),d.nextAttackTick(),d.saleValue()));
            assertEquals(256,session.bulkPurchases);assertEquals(SummonTier.atRound(gate),session.arena.summonTier());
            games.suspendPresentation();
            return state;
        }
    }
    @Test void purchasesPromotionAndTierTransitionMatchAcrossSpeedsWithAsyncPlacement()throws Exception {
        for(int gate:new int[]{500,1000})assertEquals(purchasesAtSpeed(1,gate),purchasesAtSpeed(32,gate));
    }
}
