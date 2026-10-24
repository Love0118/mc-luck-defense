package dev.moma.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class SpectatorCompassTest {
    @Test void spectatorCanOpenMenuOutsideLobbyOnlyWithMainHandRightClick()throws Exception {
        var games=mock(GameService.class);var tools=mock(SessionTools.class);
        var field=GameService.class.getDeclaredField("tools");field.setAccessible(true);field.set(games,tools);
        var lobby=mock(Lobby.class);var menu=mock(MinigameMenu.class);var player=mock(Player.class);
        when(games.watching(player)).thenReturn(true);when(games.active(player)).thenReturn(true);
        when(tools.holding(player,"sessions")).thenReturn(true);
        var sessions=mock(LobbyMenu.class);var listener=new LobbyListener(lobby,games,sessions,menu);var event=mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);listener.interact(event);
        verify(menu).open(player);verify(event).setCancelled(true);clearInvocations(menu);
        verifyNoInteractions(sessions);
        var swap=mock(org.bukkit.event.player.PlayerSwapHandItemsEvent.class);when(swap.getPlayer()).thenReturn(player);
        listener.swap(swap);verify(sessions).open(player);verify(swap).setCancelled(true);verifyNoInteractions(menu);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);listener.interact(event);verifyNoInteractions(menu);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        listener.interact(event);verifyNoInteractions(menu);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);when(games.watching(player)).thenReturn(false);
        listener.interact(event);verifyNoInteractions(menu);
    }
}
