package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeedMenuTest {
    @Test void shopClockOpensSixChoicesWithoutChangingSpeedAndSelectionReturnsToShop() {
        MomaPlugin plugin=mock(MomaPlugin.class);GameService games=mock(GameService.class);
        Player player=mock(Player.class);World world=mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        GameSession session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        when(games.session(player)).thenReturn(session);
        InventoryView view=mock(InventoryView.class);when(player.getOpenInventory()).thenReturn(view);
        var inventories=new ArrayList<Inventory>();
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));inventories.add(inventory);return inventory;
            });
            doAnswer(call->{when(view.getTopInventory()).thenReturn(call.getArgument(0));return view;}).when(player).openInventory(any(Inventory.class));
            ShopMenu shop=new ShopMenu(plugin,games);shop.open(player);
            InventoryClickEvent event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);
            when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(8);shop.click(event);
            assertEquals(2,inventories.size());verify(games,never()).speed(any(),anyInt());
            Inventory speed= view.getTopInventory();
            for(int slot=10;slot<=15;slot++)verify(speed).setItem(eq(slot),any());
            when(event.getRawSlot()).thenReturn(15);shop.click(event);verify(games).speed(player,32);
            assertEquals(3,inventories.size());assertNotSame(speed,view.getTopInventory());
        }
    }
    @Test void pickerRejectsForeignStaleDuplicateBottomShiftAndDragActions() {
        GameService games=mock(GameService.class);ShopMenu shop=mock(ShopMenu.class);Player player=mock(Player.class);World world=mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getLocation()).thenReturn(new Location(world,0,65,0));when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        GameSession session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());when(games.session(player)).thenReturn(session);
        InventoryView view=mock(InventoryView.class);when(player.getOpenInventory()).thenReturn(view);
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;
            });
            doAnswer(call->{when(view.getTopInventory()).thenReturn(call.getArgument(0));return view;}).when(player).openInventory(any(Inventory.class));
            SpeedMenu menu=new SpeedMenu(games,shop);
            var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);
            for(int i=0;i<6;i++) {
                menu.open(player);when(event.getRawSlot()).thenReturn(10+i);menu.click(event);menu.click(event);
                verify(games,times(1)).speed(player,1<<i);
            }
            clearInvocations(games,shop);menu.open(player);
            when(event.getRawSlot()).thenReturn(37);menu.click(event);
            when(event.getRawSlot()).thenReturn(10);when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);menu.click(event);
            when(event.getClick()).thenReturn(ClickType.LEFT);Player stranger=mock(Player.class);when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
            when(event.getWhoClicked()).thenReturn(stranger);menu.click(event);when(event.getWhoClicked()).thenReturn(player);
            when(games.session(player)).thenReturn(null);menu.click(event);when(games.session(player)).thenReturn(session);
            Inventory original=view.getTopInventory();when(player.getOpenInventory()).thenReturn(mock(InventoryView.class));menu.click(event);
            when(player.getOpenInventory()).thenReturn(view);
            var close=mock(InventoryCloseEvent.class);when(close.getInventory()).thenReturn(original);menu.close(close);menu.click(event);
            verify(games,never()).speed(any(),anyInt());
            var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);menu.drag(drag);verify(drag).setCancelled(true);
            menu.open(player);when(event.getRawSlot()).thenReturn(22);menu.click(event);verify(shop).open(player);verify(games,never()).speed(any(),anyInt());
        }
    }
}
