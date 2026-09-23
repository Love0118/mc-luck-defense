package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReserveEffectsTest {
    @Test void benchSaleAndMovementWithinAnAcceleratedFrameDiscardStaleAttackEffects() {
        Arena a=new Arena("a",UUID.randomUUID(),new Grid(6),100,100);
        a.summon(a.owner(),new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        Defender d=a.lastSummoned();AttackEffects effects=new AttackEffects();
        effects.hit(d,new Point(-3,0));a.select(a.owner(),d.entityId());a.benchSelected(a.owner());
        effects.retainActive(a.activeDefenders());effects.forEachPrimary((unit,target)->fail("Benched attacker"));
        assertDoesNotThrow(()->effects.render(null,List.of()));
        a.select(a.owner(),d.entityId());a.moveSelected(a.owner(),new Cell(0,0));effects.clear();effects.hit(d,new Point(-3,0));
        a.select(a.owner(),d.entityId());a.moveSelected(a.owner(),new Cell(1,0));effects.retainActive(a.activeDefenders());
        effects.forEachPrimary((unit,target)->fail("Attack from old cell"));
        effects.clear();effects.hit(d,new Point(-3,0));a.select(a.owner(),d.entityId());a.sellSelected(a.owner());
        effects.retainActive(a.activeDefenders());effects.forEachPrimary((unit,target)->fail("Sold attacker"));
    }
}
