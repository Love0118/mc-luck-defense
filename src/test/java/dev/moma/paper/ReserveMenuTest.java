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

class ReserveMenuTest {
    @Test void pagesExposeAllFortyEightAndRejectStaleForeignBottomAndShiftClicks() {
        GameService games=mock(GameService.class);ShopMenu shop=mock(ShopMenu.class);Player p=mock(Player.class);World world=mock(World.class);
        UUID owner=UUID.randomUUID();when(p.getUniqueId()).thenReturn(owner);when(p.getLocation()).thenReturn(new Location(world,0,70,0));
        when(p.getGameMode()).thenReturn(GameMode.ADVENTURE);when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        GameSession session=new GameSession(p,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
        session.arena.credit(1000);session.arena.toggleMerging(owner);
        for(int i=0;i<84;i++)session.arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.COMMON),(t,r,c)->UUID.randomUUID());
        when(games.session(p)).thenReturn(session);ReserveMenu menu=new ReserveMenu(games,shop);
        InventoryView view=mock(InventoryView.class);when(p.getOpenInventory()).thenReturn(view);
        List<Inventory> inventories=new ArrayList<>();
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));inventories.add(inventory);return inventory;
            });
            doAnswer(call->{when(view.getTopInventory()).thenReturn(call.getArgument(0));return view;}).when(p).openInventory(any(Inventory.class));
            menu.open(p,0);Inventory first=inventories.getFirst();verify(first).setItem(eq(44),any(ItemStack.class));verify(first).setItem(eq(53),any(ItemStack.class));
            var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(p);when(event.getClick()).thenReturn(ClickType.LEFT);
            when(event.getRawSlot()).thenReturn(53);assertTrue(menu.click(event));assertEquals(2,inventories.size());
            Inventory second=inventories.getLast();verify(second).setItem(eq(2),any(ItemStack.class));verify(second,never()).setItem(eq(3),any(ItemStack.class));
            when(event.getRawSlot()).thenReturn(54);menu.click(event);verify(games,never()).select(any(),any());
            when(event.getRawSlot()).thenReturn(0);when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);menu.click(event);verify(games,never()).select(any(),any());
            Player stranger=mock(Player.class);when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());when(event.getWhoClicked()).thenReturn(stranger);
            when(event.getClick()).thenReturn(ClickType.LEFT);menu.click(event);verify(games,never()).select(any(),any());
            when(event.getWhoClicked()).thenReturn(p);menu.click(event);menu.click(event);
            verify(games,times(1)).select(p,session.arena.reserveUnits().get(45).entityId());verify(p.getInventory()).setHeldItemSlot(1);
            var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);menu.drag(drag);verify(drag).setCancelled(true);
            menu.open(p,0);when(games.session(p)).thenReturn(null);menu.click(event);verify(games,times(1)).select(any(),any());
        }
    }
    @Test void manualRightClickSellsOnceAndReopensReserve() {
        GameService games=mock(GameService.class);Player p=mock(Player.class);World world=mock(World.class);UUID owner=UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(owner);when(p.getLocation()).thenReturn(new Location(world,0,70,0));when(p.getGameMode()).thenReturn(GameMode.ADVENTURE);
        GameSession s=new GameSession(p,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());s.arena.credit(10);
        s.arena.summon(owner,new SummonRoll(UnitType.WOLF,Rarity.TRUE_PRIMORDIAL),(t,r,c)->UUID.randomUUID());
        s.arena.select(owner,s.arena.lastSummoned().entityId());s.arena.benchSelected(owner);
        when(games.session(p)).thenReturn(s);doAnswer(call->{s.arena.select(owner,call.getArgument(1));return null;}).when(games).select(eq(p),any());var menu=new ReserveMenu(games,mock(ShopMenu.class));
        InventoryView view=mock(InventoryView.class);when(p.getOpenInventory()).thenReturn(view);
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;
            });
            doAnswer(call->{when(view.getTopInventory()).thenReturn(call.getArgument(0));return view;}).when(p).openInventory(any(Inventory.class));
            menu.open(p,0);Inventory original=view.getTopInventory();InventoryView oldView=mock(InventoryView.class);when(oldView.getTopInventory()).thenReturn(original);
            doAnswer(call->{s.arena.sellSelected(owner);return null;}).when(games).sell(p);
            var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(oldView);when(event.getWhoClicked()).thenReturn(p);
            when(event.getClick()).thenReturn(ClickType.RIGHT);when(event.getRawSlot()).thenReturn(0);
            menu.click(event);menu.click(event);verify(games,times(1)).sell(p);assertEquals(0,s.arena.reserveCount());
        }
    }
    @Test void miracleNamesCarrySeveralColorsAndNoItalic() {
        var text=EntityAdapter.rarityName(Rarity.MIRACLE,"[미라클] 늑대");
        assertTrue(text.children().stream().map(c->c.color()).distinct().count()>2);
        assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,text.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));
    }
}
