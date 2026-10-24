package dev.moma.mining;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningMenuTest {
    @SuppressWarnings("unchecked") private static <T>T field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(owner);
    }
    private static void field(Object owner,String name,Object value)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Player participant(MiningGame game)throws Exception {
        var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());MiningMenuTest.<Set<UUID>>field(game,"active").add(player.getUniqueId());return player;
    }
    @Test void foreignSharedShopAndSettingsHoldersAreNeverConsumedAsMineActions()throws Exception {
        var host=mock(MiningGame.Host.class);var game=new MiningGame(mock(JavaPlugin.class),host);var player=participant(game);
        var holder=mock(InventoryHolder.class);var top=mock(Inventory.class);when(top.getHolder()).thenReturn(holder);
        var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(top);var click=mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);when(click.getView()).thenReturn(view);when(click.getClickedInventory()).thenReturn(top);
        game.click(click);verify(click).setCancelled(true);verifyNoInteractions(host);
        assertTrue(MiningMenuTest.<ArrayDeque<Runnable>>field(game,"menuActions").isEmpty());
    }
    @Test void doubleClickQueuesOneActionEvenIfFirstActionFailsAndLeavesSameView()throws Exception {
        var game=new MiningGame(mock(JavaPlugin.class),mock(MiningGame.Host.class));var player=participant(game);
        Class<?> type=Class.forName("dev.moma.mining.MiningGame$Menu");var ctor=type.getDeclaredConstructor(Player.class);ctor.setAccessible(true);Object menu=ctor.newInstance(player);
        var top=mock(Inventory.class);when(top.getHolder()).thenReturn((InventoryHolder)menu);field(menu,"inventory",top);
        var calls=new AtomicInteger();MiningMenuTest.<Map<Integer,Runnable>>field(menu,"actions").put(11,()->{calls.incrementAndGet();throw new IllegalArgumentException("별빛이 부족합니다.");});
        var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(top);when(player.getOpenInventory()).thenReturn(view);
        var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getView()).thenReturn(view);
        when(click.getClickedInventory()).thenReturn(top);when(click.getRawSlot()).thenReturn(11);when(click.isLeftClick()).thenReturn(true);
        game.click(click);game.click(click);var queue=MiningMenuTest.<ArrayDeque<Runnable>>field(game,"menuActions");assertEquals(1,queue.size());
        queue.remove().run();game.click(click);assertEquals(1,calls.get());assertTrue(queue.isEmpty());
    }
}
