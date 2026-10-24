package dev.moma.mining;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaveLayoutTest {
    @Test void everyVeinComponentIsConnectedAndHasAtLeastThreeCells() {
        var eligible=CaveLayout.cells();
        for(int seed=0;seed<12;seed++) {
            var ores=CaveLayout.veins(eligible,new Random(seed),.22);
            assertTrue(ores.size()>eligible.size()*.18);assertTrue(ores.size()<eligible.size()*.25);
            var unseen=new HashSet<>(ores.keySet());
            while(!unseen.isEmpty()) {
                var first=unseen.iterator().next();var queue=new ArrayDeque<CaveLayout.Cell>();queue.add(first);unseen.remove(first);int count=0;
                while(!queue.isEmpty()) {
                    var c=queue.remove();count++;assertTrue(CaveLayout.mineable(c));
                    for(var n:c.neighbors())if(Objects.equals(ores.get(c),ores.get(n)) && unseen.remove(n))queue.add(n);
                }
                assertTrue(count>=3,"Isolated vein at "+first);
            }
        }
    }
    @Test void permanentRoutesAndHubNeverBecomeMineableOrRegenerated() {
        for(var c:CaveLayout.cells()) {
            assertFalse(CaveLayout.hub(c));assertFalse(CaveLayout.air(c));
            assertFalse(CaveLayout.route(c) && c.y()<=CaveLayout.FLOOR+5);
            assertTrue(CaveLayout.inside(c));
        }
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(0,48,0)));
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(24,55,0)));
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(0,63,0)));
        assertEquals("LODESTONE",CaveLayout.shell(new CaveLayout.Cell(0,49,-21)));
    }
    @Test void allGroundLevelCavernAirIsConnectedToSpawnWithHeadroom() {
        var air=new HashSet<CaveLayout.Cell>();
        for(int x=-23;x<24;x++)for(int z=-23;z<24;z++) {
            var feet=new CaveLayout.Cell(x,49,z);var head=new CaveLayout.Cell(x,50,z);
            if(CaveLayout.shell(feet).equals("AIR") && CaveLayout.shell(head).equals("AIR"))air.add(feet);
        }
        var queue=new ArrayDeque<CaveLayout.Cell>();var spawn=new CaveLayout.Cell(0,49,-19);assertTrue(air.remove(spawn));queue.add(spawn);
        while(!queue.isEmpty())for(var n:queue.remove().neighbors())if(air.remove(n))queue.add(n);
        assertTrue(air.isEmpty(),"Unreachable walkable cavern cells: "+air.size());
    }
    @Test void regenerationFillsMinedAirButExcludesPlayerBodyAndExistingOre() {
        var cells=new LinkedHashMap<CaveLayout.Cell,String>();for(var c:CaveLayout.cells())cells.put(c,"AIR");
        var occupied=new CaveLayout.Cell(-20,49,-20);var ore=new CaveLayout.Cell(-19,49,-20);cells.put(ore,"EMERALD_ORE");
        var next=CaveLayout.refill(cells,occupied::equals,new Random(77));
        assertEquals("AIR",next.get(occupied));assertEquals("EMERALD_ORE",next.get(ore));assertEquals(cells.size(),next.size());
        assertEquals(1,next.values().stream().filter("AIR"::equals).count());
        assertTrue(next.values().stream().filter("STONE"::equals).count()>cells.size()/2);
        assertTrue(next.values().stream().anyMatch("DIAMOND_ORE"::equals));assertEquals("AIR",cells.get(occupied));
    }
}
