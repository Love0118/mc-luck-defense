package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationTest {
    private GameService games;
    private GameSession session;
    private Player player;
    private MockedConstruction<EntityAdapter> adapters;
    private MockedStatic<SummonRoll> rolls;
    private MockedStatic<Bukkit> bukkit;
    @BeforeEach void setup() {
        MomaPlugin plugin = mock(MomaPlugin.class); when(plugin.namespace()).thenReturn("momadefense");
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        player = mock(Player.class); World world = mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        adapters = mockConstruction(EntityAdapter.class,(adapter,context) ->
                when(adapter.spawnDefender(any(),any(),any(),any(),any())).thenAnswer(call -> UUID.randomUUID()));
        rolls = mockStatic(SummonRoll.class,CALLS_REAL_METHODS); bukkit = mockStatic(Bukkit.class);
        games = spy(new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard()));
        session = new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        doReturn(session).when(games).session(player);
        draw(Rarity.COMMON);
    }
    @AfterEach void close() { bukkit.close(); rolls.close(); adapters.close(); }
    private void draw(Rarity rarity) { rolls.when(() -> SummonRoll.draw(any(),anyBoolean())).thenReturn(new SummonRoll(UnitType.WOLF,rarity)); }
    @Test void spendingAndHighGradeDuplicatesOnlyCountSuccessfulUnassistedPurchases() {
        games.achievements=mock(AchievementService.class);draw(Rarity.LEGENDARY);
        games.summon(player);games.summon(player);games.summon(player);games.summon(player);
        verify(games.achievements,times(3)).spent(player,10);
        verify(games.achievements,times(2)).duplicate(player);
        assertEquals(30,session.arena.spentGold());
        session.arena.credit(1000);session.assisted=true;games.summon(player);
        verify(games.achievements,times(3)).spent(player,10);
        session.assisted=false;session.arena.reachedRound(101);
        rolls.when(()->SummonRoll.draw(any(),eq(false),eq(SummonTier.ADVANCED))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY));
        games.summon(player);verify(games.achievements).spent(player,100);
    }
    @Test void truePrimordialPromotionAwardsOnceAndBroadcastsFinalGrade() {
        session.arena.credit(1000);games.achievements=mock(AchievementService.class);
        for(int i=0;i<20;i++)session.arena.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL),(t,r,c)->UUID.randomUUID());
        draw(Rarity.PRIMORDIAL);
        try(var announcement=mockStatic(SummonAnnouncement.class)) {
            games.summon(player);
            assertEquals(Rarity.TRUE_PRIMORDIAL,session.arena.lastSummoned().rarity());
            verify(games.achievements).truePrimordialPromoted(player);
            announcement.verify(()->SummonAnnouncement.broadcast(player,new SummonRoll(UnitType.WOLF,Rarity.TRUE_PRIMORDIAL)),times(1));
            announcement.verify(()->SummonAnnouncement.broadcast(player,new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL)),never());
            games.summon(player);
            verify(games.achievements,times(1)).truePrimordialPromoted(player);
            session.arena.credit(1000);session.assisted=true;
            for(int i=0;i<20;i++)games.summon(player);
            verify(games.achievements,times(1)).truePrimordialPromoted(player);
            announcement.verify(()->SummonAnnouncement.broadcast(player,new SummonRoll(UnitType.WOLF,Rarity.TRUE_PRIMORDIAL)),times(2));
        }
    }
    @Test void bulkBuyStopsOpeningBonusOnAutoSoldRelicBeforeTheNextDraw() {
        games.toggleAutoSell(player,Rarity.RELIC);
        rolls.when(()->SummonRoll.draw(any(),eq(true))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.RELIC));
        rolls.when(()->SummonRoll.draw(any(),eq(false))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.COMMON));
        rolls.clearInvocations();
        games.toggleBulkBuy(player);games.processAutomation(player,session);
        rolls.verify(()->SummonRoll.draw(any(),eq(true)),times(1));
        rolls.verify(()->SummonRoll.draw(any(),eq(false)),times(3));
        assertFalse(session.arena.openingBonusActive()); assertEquals(1,session.arena.defenderCount());
        assertEquals(2,session.arena.defenders().getFirst().enhancement());
        assertEquals(14,session.arena.coins());
    }
    @Test void autoSalePaysExistingAndFutureUnitsExactlyOnceAndCanBeTurnedOff() {
        games.summon(player);
        UUID sold = session.arena.defenders().getFirst().entityId(); session.arena.select(player.getUniqueId(),sold);
        games.toggleAutoSell(player,Rarity.COMMON);
        assertTrue(session.autoSell.contains(Rarity.COMMON)); assertEquals(21,session.arena.coins());
        assertEquals(0,session.arena.defenderCount()); assertTrue(session.arena.selected().isEmpty());
        verify(games.entities).remove(sold);
        games.summon(player); assertEquals(12,session.arena.coins()); assertEquals(0,session.arena.defenderCount());
        verify(games.entities,times(1)).spawnDefender(any(),any(),any(),any(),any());
        games.toggleAutoSell(player,Rarity.COMMON); games.summon(player);
        assertEquals(2,session.arena.coins()); assertEquals(1,session.arena.defenderCount());
        games.toggleAutoSell(player,Rarity.PRIMORDIAL); assertTrue(session.autoSell.contains(Rarity.PRIMORDIAL));
        assertEquals(2,session.arena.coins());
    }
    @Test void traitUpgradesUseFinalAutoSaleGradeButOriginalAchievementAndSaleValue() {
        var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        AchievementStats.reached(data,250);TraitSelections.save(data,List.of("round_250"));
        session=new GameSession(player,session.map,CampaignRules.standard());doReturn(session).when(games).session(player);
        games.achievements=mock(AchievementService.class);
        games.toggleAutoSell(player,Rarity.RARE);draw(Rarity.COMMON);
        games.summon(player);assertEquals(21,session.arena.coins());assertEquals(0,session.arena.defenderCount());
        verify(games.achievements).summoned(player,Rarity.COMMON);verify(games.achievements,never()).summoned(player,Rarity.RARE);
        games.summon(player);assertEquals(12,session.arena.coins());
        games.summon(player);assertEquals(1,session.arena.defenderCount());assertEquals(Rarity.COMMON,session.arena.lastSummoned().rarity());
        session.arena.credit(10);games.summon(player);verify(games.achievements).enhanced(player);
        session.arena.credit(10);session.assisted=true;games.summon(player);verify(games.achievements,times(1)).enhanced(player);
    }
    @Test void higherPurchaseCeilingAwardsNaturalEpicAndAnnouncesTraitMythic() {
        var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        AchievementStats.reached(data,350);TraitSelections.save(data,List.of("round_350"));
        session=new GameSession(player,session.map,CampaignRules.standard());doReturn(session).when(games).session(player);
        games.achievements=mock(AchievementService.class);session.autoSell.add(Rarity.EPIC);draw(Rarity.EPIC);
        try(var announcement=mockStatic(SummonAnnouncement.class)) {
            announcement.when(()->SummonAnnouncement.global(Rarity.MYTHIC)).thenReturn(true);
            games.summon(player);
            assertEquals(Rarity.MYTHIC,session.arena.lastSummoned().rarity());assertEquals(1,session.arena.defenderCount());
            assertEquals(20,session.arena.coins());
            verify(games.achievements).summoned(player,Rarity.EPIC);
            verify(games.achievements,never()).summoned(player,Rarity.MYTHIC);
            announcement.verify(()->SummonAnnouncement.traitBroadcast(player,new SummonRoll(UnitType.WOLF,Rarity.MYTHIC)));
        }
    }
    @Test void legendaryAutoSalePaysSixtyAndOffRetainsFutureUnits() {
        draw(Rarity.LEGENDARY); games.summon(player);
        UUID sold=session.arena.defenders().getFirst().entityId();
        games.toggleAutoSell(player,Rarity.LEGENDARY);
        assertTrue(session.autoSell.contains(Rarity.LEGENDARY));
        assertEquals(80,session.arena.coins()); assertEquals(0,session.arena.defenderCount());
        verify(games.entities).remove(sold);
        games.summon(player);
        assertEquals(130,session.arena.coins()); assertEquals(0,session.arena.defenderCount());
        verify(games.entities,times(1)).spawnDefender(any(),any(),any(),any(),any());
        games.toggleAutoSell(player,Rarity.LEGENDARY); games.summon(player);
        assertEquals(120,session.arena.coins()); assertEquals(1,session.arena.defenderCount());
        games.toggleAutoSell(player,Rarity.PRIMORDIAL); assertTrue(session.autoSell.contains(Rarity.PRIMORDIAL));
        session.arena.select(player.getUniqueId(),session.arena.defenders().getFirst().entityId());
        games.sell(player); games.sell(player);
        assertEquals(180,session.arena.coins()); assertEquals(0,session.arena.defenderCount());
    }
    @Test void bulkBuyUsesRefundsAndStopsAtInsufficientFundsOrFullBoard() {
        draw(Rarity.RARE); games.toggleAutoSell(player,Rarity.RARE); games.toggleBulkBuy(player);
        games.processAutomation(player,session);
        assertEquals(3,session.bulkPurchases); assertEquals(9,session.arena.coins());
        assertFalse(session.bulkBuying); games.processAutomation(player,session);
        assertEquals(3,session.bulkPurchases); assertEquals(9,session.arena.coins());
        assertFalse(session.bulkBuying); assertEquals(0,session.arena.defenderCount());
        games.processAutomation(player,session); assertEquals(9,session.arena.coins());
        games.toggleAutoSell(player,Rarity.RARE); session.arena.credit(1000);
        int[] drawIndex={0};
        rolls.when(()->SummonRoll.draw(any(),anyBoolean())).thenAnswer(call->{int i=drawIndex[0]++;return new SummonRoll(UnitType.values()[i%24],Rarity.values()[(i/24)%9]);});
        games.toggleBulkBuy(player);
        games.processAutomation(player,session); assertEquals(4,session.arena.defenderCount());
        for (int i = 0; i < 20; i++) games.processAutomation(player,session);
        assertEquals(36,session.arena.defenderCount()); assertEquals(169,session.arena.coins()); assertEquals(48,session.arena.reserveCount());
        games.processAutomation(player,session);
        assertFalse(session.bulkBuying);
        games.toggleBulkBuy(player);games.processAutomation(player,session); assertFalse(session.bulkBuying);
    }
    @Test void profitableAutoSalesAreBoundedAndCancellationAndRejoinStopTheBatch() {
        draw(Rarity.NARRATIVE); games.toggleAutoSell(player,Rarity.NARRATIVE);
        games.toggleBulkBuy(player); games.processAutomation(player,session);
        assertEquals(4,session.bulkPurchases); assertEquals(130,session.arena.coins()); assertTrue(session.bulkBuying);
        games.toggleBulkBuy(player); games.processAutomation(player,session); assertEquals(4,session.bulkPurchases);
        games.toggleBulkBuy(player);
        GameSession replacement = new GameSession(player,session.map,CampaignRules.standard());
        doReturn(replacement).when(games).session(player);
        games.processAutomation(player,session); assertEquals(130,session.arena.coins());
        assertFalse(replacement.bulkBuying); assertFalse(replacement.autoPlacement); assertTrue(replacement.autoSell.isEmpty());
        doReturn(session).when(games).session(player); session.arena.finish(Arena.Outcome.TIME_LIMIT);
        games.processAutomation(player,session); assertEquals(130,session.arena.coins());
    }
    @Test void failedBulkSpawnDoesNotChargeAndStopsRetrying() {
        when(games.entities.spawnDefender(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("fixture"));
        games.toggleBulkBuy(player); games.processAutomation(player,session);
        assertFalse(session.bulkBuying); assertEquals(0,session.bulkPurchases);
        assertEquals(30,session.arena.coins());
        games.processAutomation(player,session);
        verify(games.entities,times(1)).spawnDefender(any(),any(),any(),any(),any());
    }
    @Test void reservePurchaseHasNoEntityUntilGradeFirstDeploymentAndCanBeBenchedAgain() {
        session.arena.credit(1000);session.arena.toggleMerging(player.getUniqueId());
        for(int i=0;i<36;i++)session.arena.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        draw(Rarity.MIRACLE);games.summon(player);
        Defender d=session.arena.lastSummoned();UUID logical=d.entityId();assertFalse(d.deployed());assertEquals(1,session.arena.reserveCount());
        verify(games.entities,never()).spawnDefender(any(),any(),any(),any(),any());
        games.toggleAutoPlacement(player);games.processAutomation(player,session);
        assertTrue(d.deployed());assertNotEquals(logical,d.entityId());assertEquals(36,session.arena.defenderCount());assertEquals(1,session.arena.reserveCount());
        UUID owner=player.getUniqueId();
        verify(games.entities).spawnDefender(any(),eq(owner),eq(UnitType.WOLF),eq(Rarity.MIRACLE),any());
        games.toggleAutoPlacement(player);games.select(player,d.entityId());games.benchSelected(player);
        assertFalse(d.deployed());assertEquals(2,session.arena.reserveCount());verify(games.entities).remove(d.entityId());
    }
    @Test void failedReserveDeploymentLeavesBothRostersAndCurrencyIntact() {
        session.arena.credit(1000);session.arena.toggleMerging(player.getUniqueId());
        for(int i=0;i<36;i++)session.arena.summon(player.getUniqueId(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        draw(Rarity.MIRACLE);games.summon(player);double coins=session.arena.coins();
        var field=session.arena.defenders();var reserve=session.arena.reserveUnits();
        when(games.entities.spawnDefender(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("fixture"));
        games.toggleAutoPlacement(player);games.processAutomation(player,session);
        assertEquals(field,session.arena.defenders());assertEquals(reserve,session.arena.reserveUnits());assertEquals(coins,session.arena.coins());
        assertFalse(session.arena.ended());verify(games.entities,never()).remove(any());
    }
    @Test void primordialAutoSaleWorksButTruePrimordialAndMiracleRequireManualSale() {
        session.arena.credit(1000);games.toggleAutoSell(player,Rarity.PRIMORDIAL);draw(Rarity.PRIMORDIAL);games.summon(player);
        assertEquals(2020,session.arena.coins());assertEquals(0,session.arena.unitCount());
        for(Rarity rarity:List.of(Rarity.TRUE_PRIMORDIAL,Rarity.MIRACLE)) {
            games.toggleAutoSell(player,rarity);assertFalse(session.autoSell.contains(rarity));
            draw(rarity);games.summon(player);Defender d=session.arena.lastSummoned();
            double before=session.arena.coins();games.select(player,d.entityId());games.sell(player);
            assertEquals(before+d.saleValue(),session.arena.coins());assertEquals(0,session.arena.unitCount());
        }
    }
    @Test void layoutRecomputesAfterSalesButOffKeepsManualPositions() {
        games.summon(player); draw(Rarity.RARE); games.summon(player);
        Defender first = session.arena.defenders().getFirst(), second = session.arena.defenders().getLast();
        session.arena.select(player.getUniqueId(),second.entityId());
        session.arena.moveSelected(player.getUniqueId(),new Cell(2,2));
        games.processAutomation(player,session); assertEquals(new Cell(2,2),second.cell());
        games.toggleAutoPlacement(player); games.processAutomation(player,session);
        assertTrue(session.map.grid().perimeter(second.cell())); assertFalse(session.layoutDirty);
        session.arena.select(player.getUniqueId(),first.entityId()); games.sell(player);
        assertTrue(session.layoutDirty); games.processAutomation(player,session); assertFalse(session.layoutDirty);
        assertEquals(new AutoPlacement(session.map.grid()).arrange(session.arena.defenders()).get(second.entityId()),second.cell());
        games.toggleAutoPlacement(player);
        session.arena.select(player.getUniqueId(),second.entityId()); session.arena.moveSelected(player.getUniqueId(),new Cell(2,2));
        games.summon(player); games.processAutomation(player,session); assertEquals(new Cell(2,2),second.cell());
    }
}
