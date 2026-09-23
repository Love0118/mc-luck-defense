package dev.moma.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static dev.moma.core.Arena.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaTest {
    @Test void meleeAndRangedPreferDifferentRegionsAndFallbackWhenFull() {
        for (UnitType type : List.of(UnitType.WOLF, UnitType.IRON_GOLEM, UnitType.SKELETON, UnitType.BLAZE)) {
            Arena arena = new Arena("placement", owner, new Grid(6), 370, 100);
            for (int i = 0; i < 36; i++) {
                UUID id = UUID.randomUUID();
                var pool=Arrays.stream(UnitType.values()).filter(t->t.role().melee()==type.role().melee()).toList();
                assertEquals(OK, arena.summon(owner, new SummonRoll(pool.get(i%pool.size()), Rarity.values()[i/pool.size()]), (t, r, cell) -> id));
                Cell placed = arena.defenders().getLast().cell();
                assertEquals(type.role().melee() ? i < 20 : i >= 16, arena.grid().perimeter(placed));
            }
            assertEquals(36, arena.defenders().stream().map(Defender::cell).distinct().count());
            assertEquals(OK, arena.summon(owner, new SummonRoll(type, Rarity.COMMON), (t, r, cell) -> { fail(); return null; }));
            assertEquals(0, arena.coins());
        }
    }
    @Test void mixedSummonsLeaveManuallyMovedUnitsAndCooldownsUntouched() {
        Arena arena = new Arena("placement", owner, new Grid(6), 100, 100);
        UUID melee = UUID.randomUUID(), ranged = UUID.randomUUID();
        arena.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.MYTHIC), (t, r, c) -> melee);
        arena.summon(owner, new SummonRoll(UnitType.SKELETON, Rarity.COMMON), (t, r, c) -> ranged);
        assertEquals(new Cell(0, 0), arena.defenders().getFirst().cell());
        assertEquals(new Cell(1, 1), arena.defenders().getLast().cell());
        Defender unit = arena.defenders().getFirst(); unit.attackAt(10, 30);
        arena.select(owner, melee); arena.moveSelected(owner, new Cell(2, 2));
        arena.summon(owner, new SummonRoll(UnitType.BLAZE, Rarity.COMMON), (t, r, c) -> UUID.randomUUID());
        assertEquals(new Cell(2, 2), unit.cell()); assertEquals(40, unit.nextAttackTick());
        arena.select(owner, ranged); arena.sellSelected(owner);
        arena.summon(owner, new SummonRoll(UnitType.WITCH, Rarity.COMMON), (t, r, c) -> UUID.randomUUID());
        assertEquals(new Cell(1, 1), arena.defenders().getLast().cell());
    }
    private final UUID owner = UUID.randomUUID();
    private Arena arena(long coins) { return new Arena("one", owner, new Grid(3), coins, 3); }
    private UUID summon(Arena arena, Rarity rarity) {
        UUID id = UUID.randomUUID();
        assertEquals(OK, arena.summon(owner, new SummonRoll(UnitType.WOLF, rarity), (t, r, c) -> id));
        return id;
    }
    @Test void placementIsClockwiseOutsideInAndHasNoDuplicates() {
        assertEquals(List.of(new Cell(0, 0), new Cell(1, 0), new Cell(2, 0), new Cell(2, 1), new Cell(2, 2),
                new Cell(1, 2), new Cell(0, 2), new Cell(0, 1), new Cell(1, 1)), new Grid(3).placementOrder());
        for (int size = 2; size <= 15; size++) {
            Grid grid = new Grid(size);
            assertEquals(size * size, new HashSet<>(grid.placementOrder()).size());
            assertTrue(grid.placementOrder().stream().allMatch(grid::contains));
        }
    }
    @Test void failedPurchasesNeverChargeOrSpawn() {
        var poor = arena(9);
        Arena.Spawner shouldNotSpawn = (t, r, c) -> { fail("Should not spawn"); return null; };
        assertEquals(INSUFFICIENT_COINS, poor.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.COMMON), shouldNotSpawn));
        assertEquals(9, poor.coins());
        var full = arena(580);full.toggleMerging(owner);
        for(int i=0;i<57;i++)summon(full,Rarity.COMMON);
        assertEquals(FULL, full.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.COMMON), shouldNotSpawn));
        assertEquals(10, full.coins());
        var failure = arena(10);
        assertThrows(IllegalStateException.class, () -> failure.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.COMMON),
                (t, r, c) -> { throw new IllegalStateException("Server spawn rejected"); }));
        assertEquals(10, failure.coins()); assertTrue(failure.defenders().isEmpty());
    }
    @Test void ownershipAndInvalidMovementPreserveSelectionAndState() {
        var arena = arena(100);
        UUID first = summon(arena, Rarity.MYTHIC);
        UUID second = summon(arena, Rarity.RARE);
        assertEquals(NOT_OWNER, arena.select(UUID.randomUUID(), first));
        assertEquals(OK, arena.select(owner, first));
        Defender defender = arena.selected().orElseThrow();
        defender.attackAt(10, 30); defender.hitTarget(second); defender.hitTarget(second);
        assertEquals(INVALID_CELL, arena.moveSelected(owner, null));
        assertEquals(INVALID_CELL, arena.moveSelected(owner, new Cell(-1, 0)));
        assertEquals(OCCUPIED, arena.moveSelected(owner, new Cell(1, 0)));
        assertEquals(defender, arena.selected().orElseThrow());
        assertEquals(NOT_OWNER, arena.moveSelected(UUID.randomUUID(), new Cell(2, 2)));
        assertEquals(OK, arena.moveSelected(owner, new Cell(2, 2)));
        assertEquals(new Cell(2, 2), defender.cell());
        assertEquals(40, defender.nextAttackTick()); assertEquals(2, defender.consecutiveHits());
        assertTrue(arena.selected().isEmpty());
        assertEquals(OK, arena.select(owner, second)); assertEquals(second, arena.selected().orElseThrow().entityId());
    }
    @ParameterizedTest @EnumSource(Rarity.class)
    void salesEnforceTierRestrictionsAndPayOnlyOnce(Rarity rarity) {
        var arena = arena(10); UUID id = summon(arena, rarity); arena.select(owner, id);
        if (rarity.salePrice().isEmpty()) {
            assertEquals(NOT_SELLABLE, arena.sellSelected(owner)); assertEquals(0, arena.coins());
            assertEquals(1, arena.defenders().size()); assertTrue(arena.selected().isPresent());
        } else {
            assertEquals(OK, arena.sellSelected(owner));
            assertEquals(rarity.salePrice().orElseThrow(), arena.coins());
            assertEquals(NO_SELECTION, arena.sellSelected(owner)); assertTrue(arena.defenders().isEmpty());
        }
    }
    @Test void soldCellIsReusedWithoutCompactingOtherUnits() {
        var arena = arena(100); UUID first = summon(arena, Rarity.COMMON); UUID second = summon(arena, Rarity.RARE);
        arena.select(owner, first); arena.sellSelected(owner); UUID third = summon(arena, Rarity.COMMON);
        assertEquals(new Cell(1, 0), arena.defenders().stream().filter(d -> d.entityId().equals(second)).findFirst().orElseThrow().cell());
        assertEquals(new Cell(0, 0), arena.defenders().stream().filter(d -> d.entityId().equals(third)).findFirst().orElseThrow().cell());
    }
    @Test void rewardsAreCreditedExactlyOnceAndNeverToAnotherArena() {
        var arena = arena(0);
        Enemy enemy = new Enemy(UUID.randomUUID(), "one", EnemyType.ZOMBIE, 10, 2, 7, false);
        assertThrows(IllegalArgumentException.class, () -> arena.addEnemy(new Enemy(UUID.randomUUID(), "two", EnemyType.HUSK, 1, 1, 99, false)));
        arena.addEnemy(enemy); enemy.damage(15);
        assertEquals(List.of(enemy.entityId()), arena.collectDeadEnemies()); assertEquals(7, arena.coins());
        assertTrue(arena.collectDeadEnemies().isEmpty()); assertEquals(0, enemy.claimRewardUnits()); assertEquals(7, arena.coins());
    }
    @Test void exactEnemyLimitEndsArenaAndStopsActions() {
        var arena = arena(100);
        for (int i = 0; i < 2; i++) arena.addEnemy(new Enemy(UUID.randomUUID(), "one", EnemyType.ZOMBIE, 10, 2, 1, false));
        assertFalse(arena.ended());
        arena.addEnemy(new Enemy(UUID.randomUUID(), "one", EnemyType.ZOMBIE, 10, 2, 1, false));
        assertTrue(arena.ended());
        assertEquals(ENDED, arena.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.COMMON), (t, r, c) -> UUID.randomUUID()));
        double progress = arena.enemies().getFirst().progress();
        assertTrue(new CombatEngine().tick(arena, 1).isEmpty());
        assertEquals(progress, arena.enemies().getFirst().progress());
    }
}
