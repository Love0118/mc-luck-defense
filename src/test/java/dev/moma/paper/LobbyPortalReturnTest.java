package dev.moma.paper;

import dev.moma.core.CampaignRules;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class LobbyPortalReturnTest {
    @Test void removesPacksBeforeTransferAndRestoresInventoryOnlyOnce() {
        try (var construction = mockConstruction(SessionTools.class)) {
            var plugin = mock(MomaPlugin.class);
            when(plugin.namespace()).thenReturn("momadefense");
            var lobby = mock(Lobby.class);
            var player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(lobby.contains(any())).thenReturn(true);
            var games = new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard(),lobby);
            games.bgm = mock(BgmService.class);
            when(games.tools.holding(player,"portal_return")).thenReturn(true);
            doAnswer(call -> {
                when(games.tools.holding(player,"portal_return")).thenReturn(false);
                return null;
            }).when(games.tools).restore(player);
            games.returnToPortal(player);
            games.returnToPortal(player);
            var order = inOrder(player,games.bgm,games.tools);
            order.verify(player).closeInventory();
            order.verify(games.bgm).stop(player);
            order.verify(games.tools).restore(player);
            order.verify(player).removeResourcePacks();
            order.verify(player).transfer("stevegallery.kr",25565);
            verify(player,times(1)).transfer(anyString(),anyInt());
        }
    }

    @Test void rejectsActivePlayersWrongItemsAndLocationsAndRestoresToolAfterFailure() {
        try (var construction = mockConstruction(SessionTools.class)) {
            var plugin = mock(MomaPlugin.class);
            when(plugin.namespace()).thenReturn("momadefense");
            var lobby = mock(Lobby.class);
            var player = mock(Player.class);
            var games = spy(new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard(),lobby));
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            games.returnToPortal(player);
            when(lobby.contains(any())).thenReturn(true);
            games.returnToPortal(player);
            when(games.tools.holding(player,"portal_return")).thenReturn(true);
            doReturn(true).when(games).playing(player);
            games.returnToPortal(player);
            doReturn(false).when(games).playing(player);
            doReturn(true).when(games).watching(player);
            games.returnToPortal(player);
            verify(player,never()).removeResourcePacks();
            verify(player,never()).transfer(anyString(),anyInt());
            doReturn(false).when(games).watching(player);
            doThrow(new IllegalStateException("Unsupported transfer")).when(player).transfer(anyString(),anyInt());
            games.returnToPortal(player);
            verify(games.tools).giveLobby(player);
        }
    }

    @Test void routesOnlyMainHandRightClicksIncludingEntityInteractions() {
        var games = mock(GameService.class);
        var lobby = mock(Lobby.class);
        var player = mock(Player.class);
        var location = mock(Location.class);
        when(player.getLocation()).thenReturn(location);
        when(lobby.contains(location)).thenReturn(true);
        var listener = new LobbyListener(lobby,games,mock(LobbyMenu.class));
        var event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(games.active(player)).thenReturn(true);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.interact(event);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        listener.interact(event);
        verify(games,never()).returnToPortal(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        listener.interact(event);
        verify(games).returnToPortal(player);
        var entityEvent = mock(PlayerInteractEntityEvent.class);
        when(entityEvent.getPlayer()).thenReturn(player);
        when(entityEvent.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.interactEntity(entityEvent);
        verify(games,times(1)).returnToPortal(player);
        when(entityEvent.getHand()).thenReturn(EquipmentSlot.HAND);
        listener.interactEntity(entityEvent);
        verify(games,times(2)).returnToPortal(player);
        var atEvent = mock(PlayerInteractAtEntityEvent.class);
        when(atEvent.getPlayer()).thenReturn(player);
        when(atEvent.getHand()).thenReturn(EquipmentSlot.HAND);
        listener.interactAtEntity(atEvent);
        verify(games,times(3)).returnToPortal(player);
    }
}
