package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FusionTest {
    @Test void duplicatesAddDamageAndMilestonesWithoutResettingCombatOrPosition() {
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID(),target=UUID.randomUUID();
        Arena arena=new Arena("fusion",owner,new Grid(6),200,100);
        SummonRoll roll=new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY);
        arena.summon(owner,roll,(t,r,c)->id);Defender unit=arena.defenders().getFirst();
        arena.select(owner,id);arena.moveSelected(owner,new Cell(2,2));arena.select(owner,id);
        unit.attackAt(5,30);unit.hitTarget(target);double base=unit.profile().damage();
        for(int i=1;i<=10;i++) {
            assertEquals(Arena.Result.OK,arena.summon(owner,roll,(t,r,c)->{fail("Merged summons must not spawn");return null;}));
            assertEquals(base*(1+i+(i/5)*.5),unit.profile().damage());
            assertEquals(60L*(i+1),unit.saleValue());assertEquals(i,unit.enhancement());
            assertEquals(35,unit.nextAttackTick());assertEquals(1,unit.consecutiveHits());
            assertEquals(new Cell(2,2),unit.cell());assertEquals(unit,arena.selected().orElseThrow());
        }
        assertEquals(1,arena.defenderCount());assertEquals(90,arena.coins());
        arena.sellSelected(owner);assertEquals(750,arena.coins());
        assertEquals(Arena.Result.NO_SELECTION,arena.sellSelected(owner));
    }
    @Test void typeGradeOwnerAndFundsAreCheckedBeforeFusion() {
        UUID owner=UUID.randomUUID();Arena arena=new Arena("fusion",owner,new Grid(6),30,100);
        for(SummonRoll roll:List.of(new SummonRoll(UnitType.WOLF,Rarity.COMMON),new SummonRoll(UnitType.WOLF,Rarity.RARE),new SummonRoll(UnitType.PANDA,Rarity.COMMON)))
            arena.summon(owner,roll,(t,r,c)->UUID.randomUUID());
        assertEquals(3,arena.defenderCount());
        assertEquals(Arena.Result.NOT_OWNER,arena.summon(UUID.randomUUID(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->{fail();return null;}));
        assertEquals(Arena.Result.INSUFFICIENT_COINS,arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->{fail();return null;}));
        assertTrue(arena.defenders().stream().allMatch(d->d.enhancement()==0));
    }
}
