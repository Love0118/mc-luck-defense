package dev.moma.mining;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningItemsTest {
    private final Player player=mock(Player.class);
    private final PlayerInventory inventory=mock(PlayerInventory.class);
    private final JavaPlugin plugin=mock(JavaPlugin.class);
    private final ItemStack[] contents=new ItemStack[36];
    private MiningItems items(){
        when(plugin.namespace()).thenReturn("momadefense");when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(i->contents.clone());when(inventory.getItem(anyInt())).thenAnswer(i->contents[(int)i.getArgument(0)]);
        doAnswer(i->{System.arraycopy(i.getArgument(0),0,contents,0,36);return null;}).when(inventory).setStorageContents(any());return new MiningItems(plugin);
    }
    private ItemStack bearer(){var item=mock(ItemStack.class);when(item.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));return item;}
    @Test void reservedToolSlotsMoveBearerItemsWithoutLosingThem(){
        var items=items();var gadget=bearer();var other=bearer();contents[4]=gadget;contents[9]=other;
        items.workspace(player);assertNull(contents[4]);assertSame(other,contents[9]);assertEquals(1,Arrays.stream(contents).filter(i->i==gadget).count());
        for(int slot:new int[]{0,1,2,3,4,7,8})assertNull(contents[slot]);
    }
    @Test void aFullSharedInventoryRejectsEntryWithoutApplyingPartialMoves(){
        var items=items();for(int slot=0;slot<36;slot++)contents[slot]=bearer();var before=contents.clone();
        assertThrows(IllegalArgumentException.class,()->items.workspace(player));assertArrayEquals(before,contents);verify(inventory,never()).setStorageContents(any());
    }
    @Test void carryCapacityAccountsForActualSharedItemsAndExistingCargoStacks(){
        var items=items();for(int slot=0;slot<36;slot++)contents[slot]=bearer();contents[9]=null;
        var profile=MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,63);
        assertTrue(items.canCarry(player,profile,MiningRules.Ore.DIAMOND,1));assertFalse(items.canCarry(player,profile,MiningRules.Ore.DIAMOND,2));
    }
}
