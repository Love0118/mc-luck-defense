package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionToolsTest {
    private PersistentDataContainer data() {
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.get(any(),any())).thenAnswer(call->values.get(call.getArgument(0)));
        when(data.getOrDefault(any(),any(),any())).thenAnswer(call->values.getOrDefault(call.getArgument(0),call.getArgument(2)));
        doAnswer(call->{values.put(call.getArgument(0),call.getArgument(2));return null;}).when(data).set(any(),any(),any());
        doAnswer(call->{values.remove(call.getArgument(0));return null;}).when(data).remove(any());
        return data;
    }
    @Test void toolsUseFixedSlotsAndRestoreSavedItemsAcrossServiceRecreation() {
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        Player player=mock(Player.class);var inventory=mock(PlayerInventory.class);
        var playerData=data();when(player.getInventory()).thenReturn(inventory);when(player.getPersistentDataContainer()).thenReturn(playerData);
        ItemStack original0=mock(ItemStack.class),original1=mock(ItemStack.class),other=mock(ItemStack.class);
        ItemStack[] slots=new ItemStack[41];slots[0]=original0;slots[1]=original1;slots[2]=other;slots[5]=other;slots[8]=other;int[] held={5};
        when(inventory.getSize()).thenReturn(slots.length);
        when(inventory.getItem(anyInt())).thenAnswer(call->slots[call.getArgument(0,Integer.class)]);
        doAnswer(call->{slots[call.getArgument(0,Integer.class)]=call.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        when(inventory.getHeldItemSlot()).thenAnswer(call->held[0]);
        doAnswer(call->{held[0]=call.getArgument(0);return null;}).when(inventory).setHeldItemSlot(anyInt());
        when(inventory.getItemInMainHand()).thenAnswer(call->slots[held[0]]);
        byte[] saved={1,2,3};
        try(var stacks=mockStatic(ItemStack.class);var items=mockConstruction(ItemStack.class,(item,context)->{
            var meta=mock(ItemMeta.class);var itemData=data();when(meta.getPersistentDataContainer()).thenReturn(itemData);
            when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);
        })) {
            stacks.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call->{
                assertArrayEquals(new ItemStack[]{original0,original1,other,null,null,other},call.getArgument(0));return saved;
            });
            stacks.when(()->ItemStack.deserializeItemsFromBytes(saved)).thenReturn(new ItemStack[]{original0,original1,other,null,null,other});
            SessionTools tools=new SessionTools(plugin);tools.give(player);
            assertTrue(tools.holding(player,"manage"));assertFalse(tools.holding(player,"move"));
            inventory.setHeldItemSlot(1);assertTrue(tools.holding(player,"move"));
            inventory.setHeldItemSlot(2);assertTrue(tools.holding(player,"sell"));
            inventory.setHeldItemSlot(8);assertTrue(tools.holding(player,"leave"));
            inventory.setHeldItemSlot(7);assertTrue(tools.holding(player,"sound"));
            assertEquals(1f,SessionTools.soundVolume(player));
            for(float level:new float[]{.5f,.25f,0f,1f}) { tools.cycleSound(player); assertEquals(level,SessionTools.soundVolume(player)); }
            slots[10]=slots[2]; // A stale copied session item must not survive cleanup.
            new SessionTools(plugin).restore(player);
            assertSame(original0,slots[0]);assertSame(original1,slots[1]);assertSame(other,slots[5]);assertSame(other,slots[8]);assertNull(slots[10]);assertEquals(5,held[0]);
            tools.restore(player);assertSame(original0,slots[0]);assertSame(original1,slots[1]);
            tools.giveLobby(player); assertTrue(tools.holding(player,"sessions"));
            inventory.setHeldItemSlot(1);assertTrue(tools.holding(player,"traits"));
            inventory.setHeldItemSlot(0);
            tools.giveLobby(player); assertTrue(tools.holding(player,"sessions"));
            tools.giveViewer(player);assertTrue(tools.holding(player,"leave"));
            inventory.setHeldItemSlot(0);assertTrue(tools.holding(player,"sessions"));assertFalse(tools.holding(player,"move"));
            inventory.setHeldItemSlot(6);assertTrue(tools.holding(player,"bgm"));
            inventory.setHeldItemSlot(7);assertTrue(tools.holding(player,"sound"));
            tools.give(player); assertTrue(tools.holding(player,"manage"));
            tools.restore(player); assertSame(original0,slots[0]); assertSame(other,slots[8]);assertSame(other,slots[2]);
            // Old five-slot backups must leave the newly managed third slot intact.
            playerData.set(new NamespacedKey(plugin,"session_hotbar_backup"),PersistentDataType.BYTE_ARRAY,saved);
            stacks.when(()->ItemStack.deserializeItemsFromBytes(saved)).thenReturn(new ItemStack[]{original0,original1,other,null,null});
            tools.restore(player);assertSame(other,slots[2]);
        }
    }
}
