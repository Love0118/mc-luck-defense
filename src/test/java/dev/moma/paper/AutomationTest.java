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
        rolls = mockStatic(SummonRoll.class); bukkit = mockStatic(Bukkit.class);
        games = spy(new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard()));
        session = new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        doReturn(session).when(games).session(player);
        draw(Rarity.COMMON);
    }
    @AfterEach void close() { bukkit.close(); rolls.close(); adapters.close(); }
    private void draw(Rarity rarity) { rolls.when(() -> SummonRoll.draw(any(),anyBoolean())).thenReturn(new SummonRoll(UnitType.WOLF,rarity)); }
    @Test void bulkBuyStopsOpeningBonusOnAutoSoldRelicBeforeTheNextDraw() {
        games.toggleAutoSell(player,Rarity.RELIC);
        rolls.when(()->SummonRoll.draw(any(),eq(true))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.RELIC));
        rolls.when(()->SummonRoll.draw(any(),eq(false))).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.COMMON));
        games.toggleBulkBuy(player);games.processAutomation(player,session);
        rolls.verify(()->SummonRoll.draw(any(),eq(true)),times(1));
        rolls.verify(()->SummonRoll.draw(any(),eq(false)),times(3));
        assertFalse(session.arena.openingBonusActive()); assertEquals(3,session.arena.defenderCount());
        assertEquals(6,session.arena.coins());
    }
    @Test void autoSalePaysExistingAndFutureUnitsExactlyOnceAndCanBeTurnedOff() {
        games.summon(player);
        UUID sold = session.arena.defenders().getFirst().entityId(); session.arena.select(player.getUniqueId(),sold);
        games.toggleAutoSell(player,Rarity.COMMON);
        assertTrue(session.autoSell.contains(Rarity.COMMON)); assertEquals(23,session.arena.coins());
        assertEquals(0,session.arena.defenderCount()); assertTrue(session.arena.selected().isEmpty());
        verify(games.entities).remove(sold);
        games.summon(player); assertEquals(16,session.arena.coins()); assertEquals(0,session.arena.defenderCount());
        verify(games.entities,times(1)).spawnDefender(any(),any(),any(),any(),any());
        games.toggleAutoSell(player,Rarity.COMMON); games.summon(player);
        assertEquals(6,session.arena.coins()); assertEquals(1,session.arena.defenderCount());
        games.toggleAutoSell(player,Rarity.PRIMORDIAL); assertFalse(session.autoSell.contains(Rarity.PRIMORDIAL));
        assertEquals(6,session.arena.coins());
    }
    @Test void bulkBuyUsesRefundsAndStopsAtInsufficientFundsOrFullBoard() {
        draw(Rarity.RARE); games.toggleAutoSell(player,Rarity.RARE); games.toggleBulkBuy(player);
        games.processAutomation(player,session);
        assertEquals(4,session.bulkPurchases); assertEquals(14,session.arena.coins());
        assertTrue(session.bulkBuying); games.processAutomation(player,session);
        assertEquals(6,session.bulkPurchases); assertEquals(6,session.arena.coins());
        assertFalse(session.bulkBuying); assertEquals(0,session.arena.defenderCount());
        games.processAutomation(player,session); assertEquals(6,session.arena.coins());
        games.toggleAutoSell(player,Rarity.RARE); session.arena.credit(1000);
        games.toggleBulkBuy(player);
        games.processAutomation(player,session); assertEquals(4,session.arena.defenderCount());
        for (int i = 0; i < 20; i++) games.processAutomation(player,session);
        assertEquals(36,session.arena.defenderCount()); assertEquals(646,session.arena.coins());
        assertFalse(session.bulkBuying);
        games.toggleBulkBuy(player); assertFalse(session.bulkBuying);
    }
    @Test void profitableAutoSalesAreBoundedAndCancellationAndRejoinStopTheBatch() {
        draw(Rarity.NARRATIVE); games.toggleAutoSell(player,Rarity.NARRATIVE);
        games.toggleBulkBuy(player); games.processAutomation(player,session);
        assertEquals(4,session.bulkPurchases); assertEquals(110,session.arena.coins()); assertTrue(session.bulkBuying);
        games.toggleBulkBuy(player); games.processAutomation(player,session); assertEquals(4,session.bulkPurchases);
        games.toggleBulkBuy(player);
        GameSession replacement = new GameSession(player,session.map,CampaignRules.standard());
        doReturn(replacement).when(games).session(player);
        games.processAutomation(player,session); assertEquals(110,session.arena.coins());
        assertFalse(replacement.bulkBuying); assertFalse(replacement.autoPlacement); assertTrue(replacement.autoSell.isEmpty());
        doReturn(session).when(games).session(player); session.arena.finish(Arena.Outcome.TIME_LIMIT);
        games.processAutomation(player,session); assertEquals(110,session.arena.coins());
    }
    @Test void failedBulkSpawnDoesNotChargeAndStopsRetrying() {
        when(games.entities.spawnDefender(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("fixture"));
        games.toggleBulkBuy(player); games.processAutomation(player,session);
        assertFalse(session.bulkBuying); assertEquals(0,session.bulkPurchases);
        assertEquals(30,session.arena.coins());
        games.processAutomation(player,session);
        verify(games.entities,times(1)).spawnDefender(any(),any(),any(),any(),any());
    }
    @Test void layoutRecomputesAfterSalesButOffKeepsManualPositions() {
        games.summon(player); games.summon(player);
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
