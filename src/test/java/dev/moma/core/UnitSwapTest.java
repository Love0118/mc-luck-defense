package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnitSwapTest {
    private Arena full() {
        Arena arena=new Arena("a",UUID.randomUUID(),new Grid(6),10000,100);
        arena.toggleMerging(arena.owner());
        for(int i=0;i<84;i++)arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        return arena;
    }
    @Test void fieldSwapPreservesCooldownEnhancementTargetAndCurrency() {
        Arena a=full();Defender first=a.defenders().get(0),second=a.defenders().get(1);
        Cell firstCell=first.cell(),secondCell=second.cell();double money=a.coins();
        first.attackAt(20,40);first.hitTarget(a.owner());first.merge(1);
        a.select(a.owner(),first.entityId());
        assertEquals(Arena.Result.OK,a.swapSelected(a.owner(),second.entityId()));
        assertEquals(secondCell,first.cell());assertEquals(firstCell,second.cell());
        assertEquals(60,first.nextAttackTick());assertEquals(1,first.consecutiveHits());assertEquals(1,first.enhancement());
        assertEquals(money,a.coins());assertTrue(a.selected().isEmpty());assertEquals(84,a.unitCount());
    }
    @Test void fieldReserveSwapWorksAtCapacityInBothDirectionsAndKeepsOtherSlots() {
        Arena a=full();var before=a.reserveUnits();Defender field=a.defenders().getFirst(),bench=before.get(23);Cell cell=field.cell();
        a.select(a.owner(),field.entityId());assertEquals(Arena.Result.OK,a.swapSelected(a.owner(),bench.entityId()));
        assertEquals(cell,bench.cell());assertNull(field.cell());assertEquals(field,a.reserveUnits().get(23));
        for(int i=0;i<48;i++)if(i!=23)assertSame(before.get(i),a.reserveUnits().get(i));
        a.select(a.owner(),field.entityId());assertEquals(Arena.Result.OK,a.swapSelected(a.owner(),bench.entityId()));
        assertEquals(cell,field.cell());assertEquals(before,a.reserveUnits());assertEquals(36,a.defenderCount());
    }
    @Test void reserveSwapOnlyReordersThoseTwoSlotsAndSurvivesSerialization()throws Exception {
        Arena a=full();var before=a.reserveUnits();Defender first=before.get(0),last=before.get(47);
        a.select(a.owner(),first.entityId());assertEquals(Arena.Result.OK,a.swapSelected(a.owner(),last.entityId()));
        assertSame(last,a.reserveUnits().getFirst());assertSame(first,a.reserveUnits().getLast());
        assertEquals(before.subList(1,47),a.reserveUnits().subList(1,47));
        Arena copy=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(a),Arena.class);
        assertEquals(a.reserveUnits().stream().map(Defender::entityId).toList(),copy.reserveUnits().stream().map(Defender::entityId).toList());
        assertTrue(copy.reserveUnits().stream().allMatch(d->!d.deployed()));assertEquals(36,copy.activeDefenders().size());
    }
    @Test void foreignMissingAndEndedTargetsCannotMutateSelectionOrPositions() {
        Arena a=full();var field=a.defenders();Defender selected=field.getFirst();Cell cell=selected.cell();
        assertEquals(Arena.Result.NO_SELECTION,a.swapSelected(a.owner(),selected.entityId()));
        a.select(a.owner(),selected.entityId());
        assertEquals(Arena.Result.NOT_OWNER,a.swapSelected(UUID.randomUUID(),field.get(1).entityId()));
        assertEquals(Arena.Result.NOT_OWNER,a.swapSelected(a.owner(),UUID.randomUUID()));
        assertEquals(cell,selected.cell());assertSame(selected,a.selected().orElseThrow());
        a.finish(Arena.Outcome.ENEMY_LIMIT);assertEquals(Arena.Result.ENDED,a.swapSelected(a.owner(),field.get(1).entityId()));
        assertSame(selected,a.selected().orElseThrow());
    }
}
