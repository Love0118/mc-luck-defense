package dev.moma.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsMenuTest {
    @Test void defaultsLegacySettingAndIndependentPreferencesPersistOnThePlayer() {
        Player player=mock(Player.class);var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        assertFalse(SessionExitPreferences.enabled(player));
        for(var option:NotificationPreferences.values())assertTrue(option.enabled(player));
        data.set(new NamespacedKey("momadefense","other_summon_alerts"),org.bukkit.persistence.PersistentDataType.BYTE,(byte)0);
        assertFalse(NotificationPreferences.OTHER_SUMMON.enabled(player));
        NotificationPreferences.OWN_SUMMON.toggle(player);NotificationPreferences.ROUND.toggle(player);
        assertFalse(NotificationPreferences.OWN_SUMMON.enabled(player));assertFalse(NotificationPreferences.ROUND.enabled(player));
        Player reconnected=mock(Player.class);when(reconnected.getPersistentDataContainer()).thenReturn(data);
        for(var option:NotificationPreferences.values())assertFalse(option.enabled(reconnected));
        NotificationPreferences.OTHER_SUMMON.toggle(reconnected);
        assertTrue(NotificationPreferences.OTHER_SUMMON.enabled(player));assertFalse(NotificationPreferences.OWN_SUMMON.enabled(player));
        SessionExitPreferences.toggle(player);assertTrue(SessionExitPreferences.enabled(reconnected));
        SessionExitPreferences.toggle(reconnected);assertFalse(SessionExitPreferences.enabled(player));
    }
    @Test void guiOpensFromTheExistingToolAndRejectsDuplicateForeignStaleAndInventoryTransferActions() {
        Player player=mock(Player.class),stranger=mock(Player.class);SessionTools tools=mock(SessionTools.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        var data=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(data);
        when(tools.holding(player,"summon_alerts")).thenReturn(true);
        InventoryView view=mock(InventoryView.class);when(player.getOpenInventory()).thenReturn(view);
        List<Inventory> inventories=new ArrayList<>();int[] tick={10};
        try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->
                when(item.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class)))) {
            bukkit.when(Bukkit::getCurrentTick).thenAnswer(call->tick[0]);
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(27),any(Component.class))).thenAnswer(call->{
                Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));inventories.add(inventory);return inventory;
            });
            doAnswer(call->{when(view.getTopInventory()).thenReturn(call.getArgument(0));return view;}).when(player).openInventory(any(Inventory.class));
            SettingsMenu menu=new SettingsMenu(tools);
            var use=mock(PlayerInteractEvent.class);when(use.getPlayer()).thenReturn(player);when(use.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
            when(use.getHand()).thenReturn(EquipmentSlot.OFF_HAND);menu.interact(use);assertTrue(inventories.isEmpty());
            when(use.getHand()).thenReturn(EquipmentSlot.HAND);menu.interact(use);menu.interact(use);
            var entity=mock(PlayerInteractEntityEvent.class);when(entity.getPlayer()).thenReturn(player);when(entity.getHand()).thenReturn(EquipmentSlot.HAND);
            menu.entityInteract(entity);assertEquals(1,inventories.size());verify(use,times(2)).setCancelled(true);
            var click=mock(InventoryClickEvent.class);when(click.getView()).thenReturn(view);when(click.getWhoClicked()).thenReturn(player);
            when(click.getClick()).thenReturn(ClickType.LEFT);
            for(int slot:new int[]{11,13,15}) {
                tick[0]++;when(click.getRawSlot()).thenReturn(slot);menu.click(click);menu.click(click);
            }
            for(var option:NotificationPreferences.values())assertFalse(option.enabled(player));
            tick[0]++;when(click.getRawSlot()).thenReturn(SettingsMenu.EXIT_LOCK);menu.click(click);menu.click(click);
            assertTrue(SessionExitPreferences.enabled(player));
            tick[0]++;when(click.getRawSlot()).thenReturn(38);menu.click(click);
            when(click.getRawSlot()).thenReturn(SettingsMenu.EXIT_LOCK);when(click.getClick()).thenReturn(ClickType.SHIFT_LEFT);menu.click(click);
            when(click.getClick()).thenReturn(ClickType.NUMBER_KEY);menu.click(click);
            when(click.getClick()).thenReturn(ClickType.LEFT);when(click.getWhoClicked()).thenReturn(stranger);menu.click(click);
            when(click.getWhoClicked()).thenReturn(player);when(player.getOpenInventory()).thenReturn(mock(InventoryView.class));menu.click(click);
            when(player.getOpenInventory()).thenReturn(view);
            var drag=mock(InventoryDragEvent.class);when(drag.getView()).thenReturn(view);menu.drag(drag);verify(drag).setCancelled(true);
            Inventory current=view.getTopInventory();var close=mock(InventoryCloseEvent.class);when(close.getInventory()).thenReturn(current);menu.close(close);menu.click(click);
            assertTrue(SessionExitPreferences.enabled(player));
            assertFalse(NotificationPreferences.OWN_SUMMON.enabled(player));
            tick[0]++;menu.interact(use);assertEquals(2,inventories.size());
            when(click.getRawSlot()).thenReturn(11);menu.click(click);assertTrue(NotificationPreferences.OWN_SUMMON.enabled(player));
            tick[0]++;when(click.getRawSlot()).thenReturn(SettingsMenu.EXIT_LOCK);menu.click(click);assertFalse(SessionExitPreferences.enabled(player));
            tick[0]++;when(click.getRawSlot()).thenReturn(22);menu.click(click);verify(player).closeInventory();
        }
    }
}
