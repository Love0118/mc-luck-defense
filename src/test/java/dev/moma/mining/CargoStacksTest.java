package dev.moma.mining;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CargoStacksTest {
    private final Player player=mock(Player.class);
    private final PlayerInventory inventory=mock(PlayerInventory.class);
    private final ItemStack[] slots=new ItemStack[41];
    private final AtomicReference<ItemStack> cursor=new AtomicReference<>();
    private MiningItems items(){
        var plugin=mock(JavaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        when(player.getInventory()).thenReturn(inventory);when(inventory.getSize()).thenReturn(41);
        when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);
        when(inventory.getItemInOffHand()).thenAnswer(c->slots[40]);when(player.getItemOnCursor()).thenAnswer(c->cursor.get());
        doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        doAnswer(c->{cursor.set(c.getArgument(0));return null;}).when(player).setItemOnCursor(nullable(ItemStack.class));return new MiningItems(plugin);
    }
    private ItemStack stack(MiningRules.Ore ore,int count){
        var item=mock(ItemStack.class);var data=mock(PersistentDataContainer.class);when(item.getPersistentDataContainer()).thenReturn(data);
        if(ore!=null){var key=new NamespacedKey("momadefense","mining_cargo");when(data.get(key,PersistentDataType.STRING)).thenReturn(ore.name());when(data.has(key,PersistentDataType.STRING)).thenReturn(true);}
        int[] amount={count};when(item.getAmount()).thenAnswer(c->amount[0]);doAnswer(c->{amount[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());return item;
    }
    @Test void oreLayoutSurvivesRefreshIncludingSplitStacksCursorAndOffhand(){
        var items=items();slots[10]=stack(MiningRules.Ore.DIAMOND,8);slots[22]=stack(MiningRules.Ore.DIAMOND,5);slots[40]=stack(MiningRules.Ore.DIAMOND,4);cursor.set(stack(MiningRules.Ore.DIAMOND,3));
        var before=slots.clone();var held=cursor.get();items.cargo(player,MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,20));
        assertArrayEquals(before,slots);assertSame(held,cursor.get());assertEquals(20,Arrays.stream(slots).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum()+cursor.get().getAmount());
        items.cargo(player,MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,22));assertEquals(10,slots[10].getAmount());assertEquals(5,slots[22].getAmount());assertEquals(3,cursor.get().getAmount());
        assertFalse(items.managed(slots[10]));
    }
    @Test void staleOreCopiesAreReconciledAndFragmentationCannotAcceptAnOreWithNoRealSlot(){
        var items=items();for(int i=0;i<36;i++)slots[i]=stack(null,1);
        slots[10]=stack(MiningRules.Ore.DIAMOND,1);slots[11]=stack(MiningRules.Ore.DIAMOND,1);slots[12]=stack(MiningRules.Ore.DIAMOND,1);
        var profile=MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,3);
        assertFalse(items.canCarry(player,profile,MiningRules.Ore.IRON,1));assertTrue(items.canCarry(player,profile,MiningRules.Ore.DIAMOND,1));
        items.cargo(player,profile.credit(MiningRules.Ore.DIAMOND,-2));assertEquals(1,slots[10].getAmount());assertNull(slots[11]);assertNull(slots[12]);assertNotNull(slots[13]);
    }
    @Test void theOffhandStackCanReceiveMoreOreEvenWithFullStorage(){
        var items=items();for(int i=0;i<36;i++)slots[i]=stack(null,1);slots[40]=stack(MiningRules.Ore.DIAMOND,63);
        var profile=MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,63);
        assertTrue(items.canCarry(player,profile,MiningRules.Ore.DIAMOND,1));assertFalse(items.canCarry(player,profile,MiningRules.Ore.DIAMOND,2));
        items.cargo(player,profile.credit(MiningRules.Ore.DIAMOND,1));assertEquals(64,slots[40].getAmount());
    }
}
