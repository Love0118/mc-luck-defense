package dev.moma.paper;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class LobbyBorderTest {
    @Test void arrivingAtMoundiResendsItsBorderAfterTeleportInsteadOfKeepingThePreviousWorldsWarning(){
        var world=mock(World.class);var player=mock(Player.class);var spawn=new Location(world,-5.5,54,4.5);
        when(player.teleport(any(Location.class))).thenReturn(true);
        new Lobby(spawn,256).send(player);
        var order=inOrder(player);order.verify(player).teleport(spawn);order.verify(player).setWorldBorder(null);
    }
    @Test void rejectedLobbyTeleportDoesNotOverwriteTheCurrentWorldsBorder(){
        var world=mock(World.class);var player=mock(Player.class);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->new Lobby(new Location(world,0,64,0),256).send(player));
        verify(player,never()).setWorldBorder(any());
    }
}
