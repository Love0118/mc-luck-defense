package dev.moma.paper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningBridgeTicketTest {
    private static PersistentDataContainer tags(){
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());return data;
    }
    @Test void mineIconRefreshesZeroToOneAndAmountsAboveAStackWithoutTouchingOtherSlots(){
        var games=mock(GameService.class);long[] count={0};boolean[] running={true};games.noqui=new NoquiHooks(){public boolean started(){return running[0];}public long ticketCount(UUID id){return count[0];}};
        var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());var inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);
        var slots=new ItemStack[41];when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);
        doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        slots[4]=mock(ItemStack.class);slots[12]=mock(ItemStack.class);var pickaxe=slots[4];var gadget=slots[12];
        var lore=new IdentityHashMap<ItemStack,List<Component>>();
        try(var construction=mockConstruction(ItemStack.class,(item,context)->{
            var meta=mock(ItemMeta.class);var data=tags();when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);when(meta.getPersistentDataContainer()).thenReturn(data);
            int[] amount={1};when(item.getAmount()).thenAnswer(c->amount[0]);doAnswer(c->{amount[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());
            doAnswer(c->{lore.put(item,c.getArgument(0));return null;}).when(meta).lore(anyList());
        })){
            var bridge=new MiningBridge(games,null,null,null);var key=new NamespacedKey("momadefense","noqui_ticket_count");
            for(long balance:new long[]{0,1,64,70,71}){
                count[0]=balance;bridge.refresh(player);assertEquals((int)Math.clamp(balance,1,64),slots[3].getAmount());
                assertEquals(balance,slots[3].getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.LONG));
                assertTrue(lore.get(slots[3]).stream().map(PlainTextComponentSerializer.plainText()::serialize).anyMatch(s->s.contains("출제권 "+balance+"장")));
                assertSame(pickaxe,slots[4]);assertSame(gadget,slots[12]);
            }
            running[0]=false;bridge.refresh(player);assertNull(slots[3]);assertSame(pickaxe,slots[4]);
        }
    }
    @Test void inventoryApiRoutesToTheCurrentMineOrDefenseWithoutRecreatingTools(){
        try(var bukkit=mockStatic(Bukkit.class)){
            var services=mock(org.bukkit.plugin.ServicesManager.class);bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            var registered=new AtomicReference<dev.noqui.api.TicketService.Inventory>();
            doAnswer(c->{registered.set(c.getArgument(1));return null;}).when(services).register(eq(dev.noqui.api.TicketService.Inventory.class),any(),any(),any());
            var games=mock(GameService.class);var mine=mock(dev.moma.mining.MiningGame.class);games.mining=mine;var bridge=mock(MiningBridge.class);var player=mock(Player.class);
            QuizTicketInventory.register(mock(MomaPlugin.class),games,bridge);when(mine.owns(player)).thenReturn(true);
            assertTrue(registered.get().ownsInventory(player));registered.get().refreshTickets(player,3);verify(bridge).refresh(player);verify(games,never()).refreshNoquiTool(any());
            when(mine.owns(player)).thenReturn(false);when(games.active(player)).thenReturn(true);
            registered.get().refreshTickets(player,4);verify(games).refreshNoquiTool(player);
        }
    }
}
