package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiReturnToolsTest {
    private static PersistentDataContainer data() {
        var result=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(result.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));
        when(result.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(result).set(any(),any(),any());
        doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(result).remove(any());return result;
    }
    private static final class Fixture implements AutoCloseable {
        final MomaPlugin plugin=mock(MomaPlugin.class);
        final Player player=mock(Player.class);
        final PlayerInventory inventory=mock(PlayerInventory.class);
        final PersistentDataContainer data=data();
        final ItemStack[] slots=new ItemStack[41];
        final Map<byte[],ItemStack[]> serialized=new IdentityHashMap<>();
        final NamespacedKey backup,extra;
        final MockedStatic<ItemStack> stacks;
        final MockedConstruction<ItemStack> items;
        int held=3,sequence;
        Fixture() {
            when(plugin.namespace()).thenReturn("momadefense");when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getInventory()).thenReturn(inventory);when(player.getPersistentDataContainer()).thenReturn(data);
            when(inventory.getSize()).thenReturn(slots.length);when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);
            doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
            when(inventory.getHeldItemSlot()).thenAnswer(c->held);doAnswer(c->{held=c.getArgument(0);return null;}).when(inventory).setHeldItemSlot(anyInt());
            when(inventory.getItemInMainHand()).thenAnswer(c->slots[held]);
            backup=new NamespacedKey(plugin,"session_hotbar_backup");extra=new NamespacedKey(plugin,"session_noqui_backup");
            stacks=mockStatic(ItemStack.class);
            stacks.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(c->{byte[] bytes={(byte)++sequence};serialized.put(bytes,c.getArgument(0,ItemStack[].class).clone());return bytes;});
            stacks.when(()->ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(c->serialized.get(c.getArgument(0)));
            items=mockConstruction(ItemStack.class,(item,context)->{var meta=mock(ItemMeta.class);var tags=data();when(meta.getPersistentDataContainer()).thenReturn(tags);when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);
                int[] count={1};when(item.getAmount()).thenAnswer(c->count[0]);doAnswer(c->{count[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());});
        }
        public void close(){items.close();stacks.close();}
    }
    @Test void startStopAndServiceRecreationRestoreOccupiedSlotAndRemoveCopiedIcons() {
        try(var f=new Fixture()) {
            var original=mock(ItemStack.class);f.slots[SessionTools.NOQUI_LOBBY_SLOT]=original;
            var tools=new SessionTools(f.plugin);tools.giveLobby(f.player);byte[] main=f.data.get(f.backup,PersistentDataType.BYTE_ARRAY);
            tools.updateNoqui(f.player,true,false,0);byte[] extra=f.data.get(f.extra,PersistentDataType.BYTE_ARRAY);
            assertEquals(7,f.serialized.get(main).length);assertArrayEquals(new ItemStack[]{original},f.serialized.get(extra));
            f.held=SessionTools.NOQUI_LOBBY_SLOT;assertTrue(tools.holding(f.player,"noqui_return"));
            f.held=3;assertFalse(tools.holding(f.player,"noqui_return"));
            f.slots[10]=f.slots[SessionTools.NOQUI_LOBBY_SLOT];tools.updateNoqui(f.player,true,false,0);assertSame(extra,f.data.get(f.extra,PersistentDataType.BYTE_ARRAY));
            new SessionTools(f.plugin).updateNoqui(f.player,false,false,0);assertSame(original,f.slots[SessionTools.NOQUI_LOBBY_SLOT]);assertNull(f.slots[10]);assertSame(main,f.data.get(f.backup,PersistentDataType.BYTE_ARRAY));
            tools.updateNoqui(f.player,true,false,0);new SessionTools(f.plugin).restore(f.player);
            assertSame(original,f.slots[SessionTools.NOQUI_LOBBY_SLOT]);assertNull(f.data.get(f.extra,PersistentDataType.BYTE_ARRAY));assertNull(f.data.get(f.backup,PersistentDataType.BYTE_ARRAY));assertEquals(3,f.held);
        }
    }
    @Test void legacyMainBackupsRemainReadableAndDowngradeRestoresNewSlotFirst() {
        for(int length:new int[]{2,5,6,7})try(var f=new Fixture()) {
            var first=mock(ItemStack.class);var second=mock(ItemStack.class);var original=mock(ItemStack.class);
            var previous=new ItemStack[length];previous[0]=first;previous[1]=second;
            f.data.set(f.backup,PersistentDataType.BYTE_ARRAY,ItemStack.serializeItemsAsBytes(previous));f.slots[SessionTools.NOQUI_SLOT]=original;
            var tools=new SessionTools(f.plugin);tools.updateNoqui(f.player,true,true,0);tools.downgrade(f.player,true);
            assertSame(original,f.slots[SessionTools.NOQUI_SLOT]);assertNull(f.data.get(f.extra,PersistentDataType.BYTE_ARRAY));
            assertEquals(length==7?6:length,f.serialized.get(f.data.get(f.backup,PersistentDataType.BYTE_ARRAY)).length);
            new SessionTools(f.plugin).restore(f.player);assertSame(first,f.slots[0]);assertSame(second,f.slots[1]);assertSame(original,f.slots[SessionTools.NOQUI_SLOT]);
        }
    }
    @Test void proxyUsesLobbyThirdAndGameFourthSlotsKeepsZeroUsableAndShowsExactCappedCount() {
        try(var f=new Fixture()) {
            var lobbyOriginal=mock(ItemStack.class);var gameOriginal=mock(ItemStack.class);f.slots[2]=lobbyOriginal;f.slots[3]=gameOriginal;var tools=new SessionTools(f.plugin);
            tools.updateNoqui(f.player,true,false,0);var zero=f.slots[2];assertNotSame(lobbyOriginal,zero);assertEquals(1,zero.getAmount());verify(zero.getItemMeta()).lore(List.of(Ui.text("&7출제권 0장"),Ui.text("&7우클릭: 로비 · 출제")));
            f.held=2;assertTrue(tools.holding(f.player,"noqui_return"));assertTrue(tools.isTool(zero));
            tools.updateNoqui(f.player,true,true,120);assertSame(lobbyOriginal,f.slots[2]);assertEquals(64,f.slots[3].getAmount());verify(f.slots[3].getItemMeta()).lore(List.of(Ui.text("&7출제권 120장"),Ui.text("&7우클릭: 로비 · 출제")));
            f.held=3;assertTrue(tools.holding(f.player,"noqui_return"));var unchanged=f.slots[3];tools.updateNoqui(f.player,true,true,120);assertSame(unchanged,f.slots[3]);
            tools.updateNoqui(f.player,true,true,2);assertEquals(2,f.slots[3].getAmount());tools.updateNoqui(f.player,false,true,2);assertSame(gameOriginal,f.slots[3]);assertSame(lobbyOriginal,f.slots[2]);
        }
    }
}
