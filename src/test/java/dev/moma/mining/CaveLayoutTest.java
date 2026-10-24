package dev.moma.mining;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaveLayoutTest {
    @Test void everyVeinComponentIsConnectedAndHasAtLeastThreeCells() {
        var eligible=CaveLayout.cells();
        for(int seed=0;seed<4;seed++) {
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
    @Test void infrastructureIsProtectedButNaturalFloorsCanBeMinedDownToBedrock() {
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(0,CaveLayout.FLOOR,0)));
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(CaveLayout.RADIUS,40,0)));
        assertEquals("BEDROCK",CaveLayout.shell(new CaveLayout.Cell(0,CaveLayout.ROOF,0)));
        assertFalse(CaveLayout.mineable(new CaveLayout.Cell(0,CaveLayout.HUB_FLOOR,-30)));
        assertTrue(CaveLayout.cells().stream().anyMatch(c->c.y()<CaveLayout.HUB_FLOOR&&c.neighbors().stream().anyMatch(CaveLayout::air)));
        for(var c:CaveLayout.cells()){assertFalse(CaveLayout.hub(c));assertFalse(CaveLayout.route(c));assertFalse(CaveLayout.air(c));}
    }
    @Test void noiseCavesVaryInHeightAndRailRouteHasTimberSupportsAndLanterns() {
        var levels=new HashSet<Integer>();for(int y=CaveLayout.FLOOR+1;y<CaveLayout.ROOF;y++)for(int x=-25;x<=25;x++)if(CaveLayout.air(new CaveLayout.Cell(x,y,10)))levels.add(y);
        assertTrue(levels.size()>12);int rails=0,lights=0,logs=0;
        for(int x=-CaveLayout.RADIUS;x<=CaveLayout.RADIUS;x++)for(int z=-CaveLayout.RADIUS;z<=CaveLayout.RADIUS;z++)for(int y=56;y<=63;y++){
            String material=CaveLayout.shell(new CaveLayout.Cell(x,y,z));if(material.equals("RAIL"))rails++;if(material.equals("LANTERN"))lights++;if(material.equals("STRIPPED_SPRUCE_LOG"))logs++;
        }
        assertTrue(rails>=75);assertTrue(lights>8);assertTrue(logs>60);
        for(int z=-20;z<=20;z++)assertEquals("RAIL",CaveLayout.shell(new CaveLayout.Cell(0,57,z)));
        assertEquals("AIR",CaveLayout.shell(new CaveLayout.Cell(0,58,-19)));
    }
    @Test void regenerationFillsMinedAirButExcludesPlayerBodyAndExistingOre() {
        var cells=new LinkedHashMap<CaveLayout.Cell,String>();for(var c:CaveLayout.cells())cells.put(c,"AIR");
        var occupied=CaveLayout.cells().get(0);var ore=CaveLayout.cells().get(1);cells.put(ore,"EMERALD_ORE");
        var next=CaveLayout.refill(cells,occupied::equals,new Random(77));
        assertEquals("AIR",next.get(occupied));assertEquals("EMERALD_ORE",next.get(ore));assertEquals(cells.size(),next.size());
        assertEquals(1,next.values().stream().filter("AIR"::equals).count());
        assertTrue(next.values().stream().filter("STONE"::equals).count()>cells.size()/2);
        assertTrue(next.values().stream().anyMatch("DIAMOND_ORE"::equals));assertEquals("AIR",cells.get(occupied));
    }
    @Test void compactShallowMineHasConnectedClimbsLightingAndSixSafeMinerStations(){
        assertEquals(48,CaveLayout.RADIUS*2);assertEquals(16,CaveLayout.HUB_FLOOR-CaveLayout.FLOOR);
        assertEquals(Set.of(45,51,57),new HashSet<>(CaveLayout.SHOPS.stream().map(CaveLayout.Cell::y).toList()));
        assertEquals(6,CaveLayout.SHOPS.size());
        for(var station:CaveLayout.SHOPS){
            assertEquals("SPRUCE_PLANKS",CaveLayout.shell(new CaveLayout.Cell(station.x(),station.y()-1,station.z())));
            assertEquals("AIR",CaveLayout.shell(station));assertEquals("AIR",CaveLayout.shell(new CaveLayout.Cell(station.x(),station.y()+1,station.z())));
        }
        for(int z:new int[]{-12,12})for(int y=44;y<=56;y++)assertEquals("LADDER",CaveLayout.shell(new CaveLayout.Cell(-2,y,z)));
        int ambient=0,lanterns=0,stairs=0;
        for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++)for(int y=41;y<68;y++){
            var c=new CaveLayout.Cell(x,y,z);var m=CaveLayout.shell(c);if(m.equals("LIGHT"))ambient++;if(m.equals("LANTERN"))lanterns++;if(m.equals("SPRUCE_STAIRS"))stairs++;
        }
        assertTrue(ambient>25);assertTrue(lanterns>=30);assertEquals(24,stairs);
    }
}
