package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraitMenuTest {
    @Test void menuRejectsLockedSlotsAndStaleClicksReplacesFamilyAndFreezesSession() {
        var player=mock(Player.class);var world=mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.hasPermission("moma.play")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        var games=mock(GameService.class);var lobby=mock(Lobby.class);when(lobby.contains(any())).thenReturn(true);
        var menu=new TraitMenu(games,lobby);var view=mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        Inventory[] current={null};when(view.getTopInventory()).thenAnswer(c->current[0]);
        try(var bukkit=mockStatic(Bukkit.class);
            var items=mockConstruction(ItemStack.class,(item,c)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(net.kyori.adventure.text.Component.class)))
                    .thenAnswer(c->{var inv=mock(Inventory.class);when(inv.getHolder()).thenReturn(c.getArgument(0));return inv;});
            doAnswer(c->{current[0]=c.getArgument(0);return null;}).when(player).openInventory(any(Inventory.class));
            menu.open(player);
            var event=mock(InventoryClickEvent.class);when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);
            var eventView=mock(InventoryView.class);when(event.getView()).thenReturn(eventView);
            Inventory first=current[0];when(eventView.getTopInventory()).thenReturn(first);when(event.getRawSlot()).thenReturn(0);
            menu.click(event);assertTrue(TraitSelections.load(data).ids().isEmpty());verify(event).setCancelled(true);
            AchievementStats.reached(data,100);menu.click(event);assertEquals(List.of("round_100"),TraitSelections.load(data).ids());
            menu.click(event);assertEquals(List.of("round_100"),TraitSelections.load(data).ids()); // old GUI event
            AchievementStats.reached(data,150);when(eventView.getTopInventory()).thenReturn(current[0]);
            int next=TraitCatalog.ALL.indexOf(TraitCatalog.find("round_150"));when(event.getRawSlot()).thenReturn(next);
            menu.click(event);assertEquals(List.of("round_150"),TraitSelections.load(data).ids());
            when(eventView.getTopInventory()).thenReturn(current[0]);when(event.getRawSlot()).thenReturn(TraitCatalog.ALL.indexOf(TraitCatalog.find("round_125")));
            menu.click(event);assertEquals(List.of("round_150"),TraitSelections.load(data).ids()); // only one slot
            var session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
            assertEquals(45,session.arena.coins());
            TraitSelections.save(data,List.of("round_100"));assertEquals(45,session.arena.coins()); // snapshot
            when(games.active(player)).thenReturn(true);
            when(eventView.getTopInventory()).thenReturn(current[0]);when(event.getRawSlot()).thenReturn(45);menu.click(event);
            assertEquals(List.of("round_100"),TraitSelections.load(data).ids()); // no change during play/spectating
        }
    }
}
