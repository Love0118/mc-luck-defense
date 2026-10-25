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
    @Test void oreAndSharedItemsCanMoveShiftDragDropAndSwapWhileMineToolsStayProtected()throws Exception{
        var game=new MiningGame(mock(JavaPlugin.class),mock(MiningGame.Host.class));var player=participant(game);var items=mock(MiningItems.class);field(game,"items",items);
        var inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);
        var top=mock(CraftingInventory.class);when(top.getMatrix()).thenReturn(new ItemStack[4]);when(top.getSize()).thenReturn(5);
        var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(top);when(view.convertSlot(20)).thenReturn(10);
        var ore=mock(ItemStack.class);var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getView()).thenReturn(view);when(click.getClickedInventory()).thenReturn(inventory);when(click.getCurrentItem()).thenReturn(ore);when(click.getSlot()).thenReturn(10);when(click.isShiftClick()).thenReturn(true);
        game.click(click);verify(click,never()).setCancelled(true);
        var drag=mock(InventoryDragEvent.class);when(drag.getWhoClicked()).thenReturn(player);when(drag.getView()).thenReturn(view);when(drag.getRawSlots()).thenReturn(Set.of(20));when(drag.getOldCursor()).thenReturn(ore);game.drag(drag);verify(drag,never()).setCancelled(true);
        var entity=mock(org.bukkit.entity.Item.class);when(entity.getItemStack()).thenReturn(ore);var drop=mock(org.bukkit.event.player.PlayerDropItemEvent.class);when(drop.getPlayer()).thenReturn(player);when(drop.getItemDrop()).thenReturn(entity);game.drop(drop);verify(drop,never()).setCancelled(true);
        var swap=mock(org.bukkit.event.player.PlayerSwapHandItemsEvent.class);when(swap.getPlayer()).thenReturn(player);when(swap.getMainHandItem()).thenReturn(ore);game.swap(swap);verify(swap,never()).setCancelled(true);
        when(items.managed(ore)).thenReturn(true);game.drop(drop);verify(drop).setCancelled(true);game.swap(swap);verify(swap).setCancelled(true);
    }
    @Test void leftClickDoesNotDenyVanillaDiggingBeforeBlockBreakCanFire()throws Exception{
        var game=new MiningGame(mock(JavaPlugin.class),mock(MiningGame.Host.class));var player=participant(game);
        var event=mock(org.bukkit.event.player.PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK);when(event.getClickedBlock()).thenReturn(mock(org.bukkit.block.Block.class));
        game.interact(event);verify(event,never()).setUseInteractedBlock(any());verify(event,never()).setUseItemInHand(any());verify(event,never()).setCancelled(true);
    }
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
    @Test void noquiOwnerCanUseSalesButCannotExecuteAMineOnlyUpgradeMenu()throws Exception {
        var host=mock(MiningGame.Host.class);var game=new MiningGame(mock(JavaPlugin.class),host);var player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(host.inSharedLobby(player)).thenReturn(true);field(game,"started",true);
        Class<?> type=Class.forName("dev.moma.mining.MiningGame$Menu");var ctor=type.getDeclaredConstructor(Player.class);ctor.setAccessible(true);Object menu=ctor.newInstance(player);
        var top=mock(Inventory.class);when(top.getHolder()).thenReturn((InventoryHolder)menu);field(menu,"inventory",top);field(menu,"sharedSales",true);
        var calls=new AtomicInteger();MiningMenuTest.<Map<Integer,Runnable>>field(menu,"actions").put(12,calls::incrementAndGet);
        var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(top);when(player.getOpenInventory()).thenReturn(view);
        var click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);when(click.getView()).thenReturn(view);when(click.getClickedInventory()).thenReturn(top);when(click.getRawSlot()).thenReturn(12);when(click.isLeftClick()).thenReturn(true);
        game.click(click);MiningMenuTest.<ArrayDeque<Runnable>>field(game,"menuActions").remove().run();assertEquals(1,calls.get());
        field(menu,"consumed",false);field(menu,"sharedSales",false);game.click(click);assertTrue(MiningMenuTest.<ArrayDeque<Runnable>>field(game,"menuActions").isEmpty());
    }
}
