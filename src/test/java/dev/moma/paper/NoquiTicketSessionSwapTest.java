package dev.moma.paper;

import dev.noqui.core.CreditLedger;
import dev.noqui.paper.QuestionTickets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoquiTicketSessionSwapTest {
    @TempDir Path directory;
    private PersistentDataContainer data(){var p=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(p.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));when(p.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));when(p.has(any(),any())).thenAnswer(c->values.containsKey(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(p).set(any(),any(),any());doAnswer(c->{values.remove(c.getArgument(0));return null;}).when(p).remove(any());return p;}
    private void refresh(QuestionTickets tickets,Player player)throws Exception{var method=QuestionTickets.class.getDeclaredMethod("refresh",Player.class);method.setAccessible(true);method.invoke(tickets,player);}
    private int count(ItemStack[] slots){return Arrays.stream(slots).filter(Objects::nonNull).filter(item->item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey("noqui","question_ticket_owner"),PersistentDataType.STRING)).mapToInt(ItemStack::getAmount).sum();}
    @Test void realMomaRestoreHidesLegacyTicketsAndCabinReentryReconcilesConsumedBalance()throws Exception {
        UUID owner=UUID.randomUUID();var ledger=new CreditLedger(directory.resolve("credits.json"));ledger.grant(owner,3);
        var plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");var player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);var playerData=data();when(player.getPersistentDataContainer()).thenReturn(playerData);
        var inventory=mock(PlayerInventory.class);ItemStack[] slots=new ItemStack[41];when(player.getInventory()).thenReturn(inventory);when(inventory.getSize()).thenReturn(41);when(inventory.getStorageContents()).thenAnswer(c->Arrays.copyOf(slots,36));when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(anyInt())).thenAnswer(c->slots[c.getArgument(0,Integer.class)]);doAnswer(c->{slots[c.getArgument(0,Integer.class)]=c.getArgument(1);return null;}).when(inventory).setItem(anyInt(),nullable(ItemStack.class));
        Map<byte[],ItemStack[]> snapshots=new IdentityHashMap<>();
        try(var stacks=mockStatic(ItemStack.class);var items=mockConstruction(ItemStack.class,(item,context)->{
            var meta=mock(ItemMeta.class);var pdc=data();when(meta.getPersistentDataContainer()).thenReturn(pdc);when(item.getItemMeta()).thenReturn(meta);when(item.hasItemMeta()).thenReturn(true);when(item.getType()).thenReturn((Material)context.arguments().getFirst());
            when(meta.getEquippable()).thenReturn(mock(org.bukkit.inventory.meta.components.EquippableComponent.class));
            int[] amount={1};when(item.getAmount()).thenAnswer(c->amount[0]);doAnswer(c->{amount[0]=c.getArgument(0);return null;}).when(item).setAmount(anyInt());
        })) {
            stacks.when(()->ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(c->{byte[] key={(byte)snapshots.size()};snapshots.put(key,((ItemStack[])c.getArgument(0)).clone());return key;});
            stacks.when(()->ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(c->snapshots.get(c.getArgument(0)).clone());
            boolean[] cabin={true};var constructor=QuestionTickets.class.getDeclaredConstructor(CreditLedger.class,BooleanSupplier.class,Predicate.class,Consumer.class);constructor.setAccessible(true);var tickets=constructor.newInstance(ledger,(BooleanSupplier)()->true,(Predicate<Player>)p->cabin[0],(Consumer<Player>)p->{});
            refresh(tickets,player);assertEquals(3,count(slots));
            var oldTicket=slots[0];cabin[0]=false;var tools=new SessionTools(plugin);tools.give(player);refresh(tickets,player);assertEquals(0,count(slots));tools.updateNoqui(player,true,true,ledger.balance(owner));assertEquals(3,slots[3].getAmount());
            UUID reserved=UUID.randomUUID();ledger.reserve(reserved,owner);ledger.spend(reserved);tools.updateNoqui(player,true,true,ledger.balance(owner));assertEquals(2,slots[3].getAmount());assertEquals(0,count(slots));
            tools.restore(player);assertTrue(count(slots)>2);refresh(tickets,player);assertEquals(0,count(slots));assertEquals(2,ledger.balance(owner));
            cabin[0]=true;slots[0]=oldTicket;refresh(tickets,player);assertEquals(2,count(slots));cabin[0]=false;refresh(tickets,player);tools.giveLobby(player);tools.updateNoqui(player,true,false,ledger.balance(owner));assertEquals(2,slots[2].getAmount());assertEquals(0,count(slots));
        }
    }
}
