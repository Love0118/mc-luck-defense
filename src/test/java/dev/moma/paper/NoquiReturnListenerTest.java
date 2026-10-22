package dev.moma.paper;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class NoquiReturnListenerTest {
    @Test void onlyMainHandRightClickUsesIconAndDuplicateEntityEventsAreGuarded()throws Exception {
        var games=mock(GameService.class);var tools=mock(SessionTools.class);var field=GameService.class.getDeclaredField("tools");field.setAccessible(true);field.set(games,tools);
        var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(tools.holding(p,"noqui_return")).thenReturn(true);when(games.canReturnToNoqui(p)).thenReturn(true);
        var listener=new NoquiReturnListener(games);var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(p);when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);listener.interact(event);verify(games,never()).requestNoquiReturn(any());
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);listener.interact(event);verify(games,never()).requestNoquiReturn(any());
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        var entity=mock(PlayerInteractEntityEvent.class);when(entity.getPlayer()).thenReturn(p);when(entity.getHand()).thenReturn(EquipmentSlot.HAND);
        var at=mock(PlayerInteractAtEntityEvent.class);when(at.getPlayer()).thenReturn(p);when(at.getHand()).thenReturn(EquipmentSlot.HAND);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(10);listener.interact(event);listener.interact(event);listener.entity(entity);listener.entityAt(at);
            verify(games,times(1)).requestNoquiReturn(p);verify(event,times(2)).setCancelled(true);verify(entity).setCancelled(true);verify(at).setCancelled(true);
            bukkit.when(Bukkit::getCurrentTick).thenReturn(11);listener.interact(event);verify(games,times(2)).requestNoquiReturn(p);
            when(tools.holding(p,"noqui_return")).thenReturn(false);listener.interact(event);verify(games,times(2)).requestNoquiReturn(p);
        }
    }
    @Test void stoppedQuizOrCrossGameCopyCannotRequestTravel()throws Exception {
        var games=mock(GameService.class);var tools=mock(SessionTools.class);var field=GameService.class.getDeclaredField("tools");field.setAccessible(true);field.set(games,tools);
        var p=mock(Player.class);when(tools.holding(p,"noqui_return")).thenReturn(true);when(games.canReturnToNoqui(p)).thenReturn(false);
        var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(p);when(event.getHand()).thenReturn(EquipmentSlot.HAND);when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        new NoquiReturnListener(games).interact(event);verify(event).setCancelled(true);verify(games).refreshNoquiTool(p);verify(games,never()).requestNoquiReturn(any());
    }
}
