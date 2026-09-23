package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReserveWorldTest {
    @Test void fortyEightSlotsAreInsideArenaOutsideCombatAndOnDistinctBlocks() {
        World world=mock(World.class);
        for(int size:new int[]{2,5,6,15}) {
            ArenaMap map=new ArenaMap("a",world,128,64,256,new Grid(size));Set<Location> positions=new HashSet<>();
            for(int slot=0;slot<48;slot++) {
                Location at=map.reserveLocation(slot);assertTrue(positions.add(at));assertTrue(map.contains(at));assertNull(map.recovery(at,true));
                assertTrue(at.getZ()<map.originZ()-6);assertEquals(65,at.getY());
                Block block=mock(Block.class);when(block.getWorld()).thenReturn(world);when(block.getY()).thenReturn(64);
                when(block.getX()).thenReturn(at.getBlockX());when(block.getZ()).thenReturn(at.getBlockZ());assertNull(map.cellAt(block));
            }
            assertThrows(IllegalArgumentException.class,()->map.reserveLocation(48));
        }
    }
    @Test void expansionIsIdempotentOpensOldRailAndRejectsOccupiedFloorBeforeWriting() {
        World world=mock(World.class);Map<List<Integer>,Block> blocks=new HashMap<>();
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->blocks.computeIfAbsent(List.of(call.getArgument(0),call.getArgument(1),call.getArgument(2)),key->{
            Block block=mock(Block.class);Material[] type={key.get(1)==65 && key.get(2)==-6?Material.GLASS:Material.AIR};
            when(block.getType()).thenAnswer(c->type[0]);when(block.isEmpty()).thenAnswer(c->type[0]==Material.AIR);
            doAnswer(c->{type[0]=c.getArgument(0);return null;}).when(block).setType(any(),eq(false));return block;
        }));
        ArenaMap map=new ArenaMap("a",world,0,64,0,new Grid(6));map.buildReserve();map.buildReserve();
        assertEquals(Material.AIR,world.getBlockAt(0,65,-6).getType());
        assertEquals(Material.PURPLE_CONCRETE,world.getBlockAt(15,64,-30).getType());
        assertEquals(Material.GLASS,world.getBlockAt(0,65,-33).getType());
        Block foreign=world.getBlockAt(0,64,-12);foreign.setType(Material.DIAMOND_BLOCK,false);
        blocks.values().forEach(org.mockito.Mockito::clearInvocations);
        assertThrows(IllegalStateException.class,map::buildReserve);
        for(Block block:blocks.values())verify(block,never()).setType(any(),anyBoolean());
    }
}
