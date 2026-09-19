package dev.moma.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.moma.core.Arena.Result.*;

class AutoPlacementTest {
    @Test void assignmentMatchesExhaustiveBestForMixedRangesAndGrades() {
        Grid grid = new Grid(6);
        var optimizer = new AutoPlacement(grid);
        UUID owner = UUID.randomUUID();
        List<Defender> units = List.of(
                new Defender(UUID.randomUUID(), owner, "a", UnitType.WOLF, Rarity.MYTHIC, new Cell(2,2)),
                new Defender(UUID.randomUUID(), owner, "a", UnitType.SKELETON, Rarity.COMMON, new Cell(3,3)),
                new Defender(UUID.randomUUID(), owner, "a", UnitType.BLAZE, Rarity.PRIMORDIAL, new Cell(0,0)));
        double best = 0;
        for (int a = 0; a < 36; a++) for (int b = 0; b < 36; b++) if (a != b)
            for (int c = 0; c < 36; c++) if (c != a && c != b)
                best = Math.max(best, optimizer.score(units.get(0),a) + optimizer.score(units.get(1),b) + optimizer.score(units.get(2),c));
        var layout = optimizer.arrange(units);
        double actual = units.stream().mapToDouble(d -> optimizer.score(d, grid.placementOrder().indexOf(layout.get(d.entityId())))).sum();
        assertEquals(best, actual, 1e-6);
        assertEquals(3, new HashSet<>(layout.values()).size());
        assertTrue(grid.perimeter(layout.get(units.getFirst().entityId())));
        assertEquals(Map.of(), optimizer.arrange(List.of()));
    }

    @Test void fullBoardSwapsAreAtomicAndPreserveAttackStateAndSelection() {
        UUID owner = UUID.randomUUID();
        Arena arena = new Arena("a", owner, new Grid(6), 360, 100);
        for (int i = 0; i < 36; i++)
            arena.summon(owner, new SummonRoll(UnitType.values()[i % 24], Rarity.values()[i % 9]), (t,r,c) -> UUID.randomUUID());
        List<Defender> units = arena.defenders();
        Defender first = units.getFirst(); first.attackAt(100,40); first.hitTarget(owner); first.hitTarget(owner);
        arena.select(owner,first.entityId());
        Map<UUID,Cell> layout = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) layout.put(units.get(i).entityId(), units.get((i+1)%36).cell());
        assertEquals(NOT_OWNER, arena.rearrange(UUID.randomUUID(),layout));
        assertEquals(OK, arena.rearrange(owner,layout));
        assertEquals(36, arena.defenders().stream().map(Defender::cell).distinct().count());
        assertEquals(140,first.nextAttackTick()); assertEquals(2,first.consecutiveHits());
        assertSame(first,arena.selected().orElseThrow()); assertEquals(0,arena.coins());
        Map<UUID,Cell> invalid = new LinkedHashMap<>(layout);
        invalid.put(first.entityId(),new Cell(-1,0));
        assertEquals(INVALID_CELL,arena.rearrange(owner,invalid));
        invalid.put(first.entityId(),units.getLast().cell());
        assertEquals(OCCUPIED,arena.rearrange(owner,invalid));
        invalid.remove(first.entityId()); assertEquals(NOT_OWNER,arena.rearrange(owner,invalid));
        for (Defender d : units) assertEquals(layout.get(d.entityId()),d.cell());
        AutoPlacement optimizer = new AutoPlacement(arena.grid());
        assertEquals(OK,arena.rearrange(owner,optimizer.arrange(units)));
        assertEquals(140,first.nextAttackTick());
        var stable = optimizer.arrange(units);
        for (Defender d : units) assertEquals(d.cell(),stable.get(d.entityId()));
        arena.finish(Arena.Outcome.TIME_LIMIT);
        assertEquals(ENDED,arena.rearrange(owner,layout));
    }
}
