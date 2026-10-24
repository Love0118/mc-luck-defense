package dev.moma.mining;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningDigTest {
    @TempDir Path directory;
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    @Test void oresGoStraightToInventoryWithFortuneAndStoneNeverCreatesItems()throws Exception{
        var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);when(inventory.getHeldItemSlot()).thenReturn(4);
        var game=new MiningGame(mock(JavaPlugin.class),mock(MiningGame.Host.class));var cave=mock(MiningWorld.class);var items=mock(MiningItems.class);
        set(game,"active",new HashSet<>(Set.of(id)));set(game,"cave",cave);set(game,"items",items);
        when(cave.ready()).thenReturn(true);when(cave.belongs(any())).thenReturn(true);when(items.owned(any(),eq(id),any())).thenReturn(true);when(items.canCarry(any(),any(),any(),anyInt())).thenReturn(true);
        var cells=CaveLayout.cells();var ore=cells.get(0);var stone=cells.get(1);
        try(var store=new MiningStore(directory.resolve("mine.db"))){
            var fresh=MiningStoreTest.session();store.save(id,new MiningStore.Profile(fresh.tool(),50,3,Map.of(),fresh.recovery(),null));set(game,"store",store);
            store.replaceCells(Map.of(ore,"DIAMOND_ORE",stone,"STONE"),1);
            for(var cell:List.of(ore,stone)){
                var block=mock(Block.class);when(block.getX()).thenReturn(cell.x());when(block.getY()).thenReturn(cell.y());when(block.getZ()).thenReturn(cell.z());
                String material=cell.equals(ore)?"DIAMOND_ORE":"STONE";when(block.getType()).thenReturn(Material.valueOf(material));when(cave.material(cell)).thenReturn(material);
                var event=new BlockBreakEvent(block,player);game.mine(event);assertTrue(event.isCancelled());assertFalse(event.isDropItems());assertEquals(0,event.getExpToDrop());verify(cave).mined(cell);
            }
            long count=store.profile(id).cargo().get(MiningRules.Ore.DIAMOND);assertTrue(count>=1&&count<=4);
            verify(items,times(1)).cargo(eq(player),any());assertEquals(1,store.profile(id).cargo().size());
            var full=cells.get(2);store.replaceCells(Map.of(full,"DIAMOND_ORE"),1);when(cave.material(full)).thenReturn("DIAMOND_ORE");when(items.canCarry(any(),any(),any(),anyInt())).thenReturn(false);
            var block=mock(Block.class);when(block.getX()).thenReturn(full.x());when(block.getY()).thenReturn(full.y());when(block.getZ()).thenReturn(full.z());when(block.getType()).thenReturn(Material.DIAMOND_ORE);
            game.mine(new BlockBreakEvent(block,player));assertEquals("DIAMOND_ORE",store.cells().get(full));verify(cave,never()).mined(full);
        }
    }
}
