package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BulkSaleTest {
    @Test void exactGradeSaleCreditsOnceClearsSelectionAndLeavesOtherGrades() {
        UUID owner=UUID.randomUUID(); Arena arena=new Arena("a",owner,new Grid(6),1000,100);
        for (Rarity grade:Rarity.values()) for (int i=0;i<2;i++)
            assertEquals(Arena.Result.OK,arena.summon(owner,new SummonRoll(UnitType.WOLF,grade),(t,r,c)->UUID.randomUUID()));
        UUID selected=arena.defenders().stream().filter(d->d.rarity()==Rarity.RARE).findFirst().orElseThrow().entityId();
        arena.select(owner,selected); long before=arena.coins();
        var sale=arena.sellRarity(owner,Rarity.RARE);
        assertEquals(Arena.Result.OK,sale.result()); assertEquals(2,sale.entities().size());
        assertEquals(12,sale.income()); assertEquals(before+12,arena.coins());
        assertTrue(arena.selected().isEmpty()); assertEquals(16,arena.defenderCount());
        assertTrue(arena.activeDefenders().stream().noneMatch(d->d.rarity()==Rarity.RARE));
        var repeat=arena.sellRarity(owner,Rarity.RARE);
        assertEquals(0,repeat.income()); assertTrue(repeat.entities().isEmpty()); assertEquals(before+12,arena.coins());
        assertEquals(Arena.Result.OK,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID()));
    }
    @Test void foreignEndedAndUnsellableRequestsDoNotMutateAnything() {
        UUID owner=UUID.randomUUID(); Arena arena=new Arena("a",owner,new Grid(6),100,100);
        for(Rarity grade:List.of(Rarity.COMMON,Rarity.LEGENDARY,Rarity.PRIMORDIAL))
            arena.summon(owner,new SummonRoll(UnitType.WOLF,grade),(t,r,c)->UUID.randomUUID());
        long coins=arena.coins(); var units=arena.defenders();
        assertEquals(Arena.Result.NOT_OWNER,arena.sellRarity(UUID.randomUUID(),Rarity.COMMON).result());
        assertEquals(Arena.Result.NOT_SELLABLE,arena.sellRarity(owner,Rarity.LEGENDARY).result());
        assertEquals(Arena.Result.NOT_SELLABLE,arena.sellRarity(owner,Rarity.PRIMORDIAL).result());
        arena.finish(Arena.Outcome.ENEMY_LIMIT);
        assertEquals(Arena.Result.ENDED,arena.sellRarity(owner,Rarity.COMMON).result());
        assertEquals(coins,arena.coins()); assertEquals(units,arena.defenders());
    }
}
