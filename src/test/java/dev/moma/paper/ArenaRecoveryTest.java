package dev.moma.paper;

import dev.moma.core.Grid;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArenaRecoveryTest {
    @Test void airborneViewpointsRemainUntouchedInsideEveryEdge() {
        World world=mock(World.class);
        ArenaMap map=new ArenaMap("a",world,128,64,256,new Grid(6));
        for(double x:new double[]{122,128,149.999}) for(double z:new double[]{250,256,277.999})
            for(double y:new double[]{65,80,104}) {
                Location position=new Location(world,x,y,z,123,-35);
                assertNull(map.recovery(position,false)); assertNull(map.recovery(position,true));
            }
    }
    @Test void crossingAnyEdgeKeepsAltitudeAndFacingNearTheCrossing() {
        World world=mock(World.class);
        ArenaMap map=new ArenaMap("a",world,128,64,256,new Grid(6));
        for(Location current:new Location[]{new Location(world,121.99,88,262,72,-24),
                new Location(world,150,88,262,72,-24), new Location(world,133,88,222.99,72,-24),
                new Location(world,133,88,278,72,-24)}) for(boolean spectator:new boolean[]{false,true}) {
            Location fixed=map.recovery(current,spectator);
            assertTrue(map.contains(fixed)); assertTrue(fixed.distance(current)<1);
            assertEquals(current.getY(),fixed.getY()); assertEquals(72,fixed.getYaw()); assertEquals(-24,fixed.getPitch());
            assertNull(map.recovery(fixed,spectator));
            assertEquals(88,current.getY());
        }
    }
    @Test void floorCeilingAndWrongWorldRecoverWithoutDroppingProtection() {
        World world=mock(World.class);
        ArenaMap map=new ArenaMap("a",world,0,64,0,new Grid(6));
        Location below=new Location(world,10,20,12,90,30);
        Location fixed=map.recovery(below,false);
        assertEquals(10,fixed.getX()); assertEquals(12,fixed.getZ()); assertEquals(66,fixed.getY());
        Location high=new Location(world,10,105,12,90,30);
        assertNull(map.recovery(high,false)); assertEquals(104,map.recovery(high,true).getY());
        Location wrongWorld=new Location(mock(World.class),10,90,12,90,30);
        Location returned=map.recovery(wrongWorld,false);
        assertEquals(world,returned.getWorld()); assertTrue(map.contains(returned));
        assertEquals(90,returned.getYaw()); assertEquals(30,returned.getPitch());
    }
}
