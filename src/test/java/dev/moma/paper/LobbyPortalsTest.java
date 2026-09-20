package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class LobbyPortalsTest {
    @Test void menuWaitsTenTicksAndAbortsIfPlayerStartsPlayingDuringDelay() {
        var player=mock(Player.class);var world=mock(World.class);var portal=mock(Block.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0,65,0,.6,66.8,.6));
        when(player.hasPermission("moma.play")).thenReturn(true);when(player.isOnline()).thenReturn(true);
        when(portal.getType()).thenReturn(Material.NETHER_PORTAL);when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(portal);
        when(player.teleport(any(Location.class))).thenReturn(true);
        var games=mock(GameService.class);var menu=mock(LobbyMenu.class);var plugin=mock(MomaPlugin.class);
        var lobby=new Lobby(new Location(world,10,65,10),256);
        var listener=new LobbyPortals(plugin,lobby,games,menu);
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            listener.run();listener.run();
            verify(player,times(1)).teleport(lobby.spawn()); verifyNoInteractions(menu);
            var callback=org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler,times(1)).runTaskLater(eq(plugin),callback.capture(),eq(10L));
            when(player.getBoundingBox()).thenReturn(new BoundingBox(0,0,0,0,0,0));
            callback.getValue().run();verify(menu,times(1)).open(player);
            listener.run();when(player.getBoundingBox()).thenReturn(new BoundingBox(0,65,0,.6,66.8,.6));
            listener.run();verify(scheduler,times(2)).runTaskLater(eq(plugin),callback.capture(),eq(10L));
            when(games.active(player)).thenReturn(true);callback.getValue().run();verify(menu,times(1)).open(player);
        }
    }
    @Test void portalEntryOpensOnceReentryRearmsAndOtherPlayersAreExcluded() {
        World world=mock(World.class); Player player=mock(Player.class);
        when(player.getWorld()).thenReturn(world); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(-.1,65,0,.5,66.8,.6));
        when(player.hasPermission("moma.play")).thenReturn(true);
        Block air=mock(Block.class),portal=mock(Block.class);when(air.getType()).thenReturn(Material.AIR);when(portal.getType()).thenReturn(Material.NETHER_PORTAL);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(air);
        when(world.getBlockAt(-1,66,0)).thenReturn(portal);
        GameService games=mock(GameService.class);LobbyMenu menu=mock(LobbyMenu.class);
        LobbyPortals listener=new LobbyPortals(mock(MomaPlugin.class),new Lobby(new Location(world,0,65,0),256),games,menu);
        when(player.teleport(any(Location.class))).thenReturn(true); when(player.isOnline()).thenReturn(true);
        try(var bukkit=mockStatic(Bukkit.class)) {
            var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class),any(Runnable.class),eq(10L))).thenAnswer(call->{var box=player.getBoundingBox(); when(player.getBoundingBox()).thenReturn(new BoundingBox(0,0,0,0,0,0)); call.getArgument(1,Runnable.class).run(); when(player.getBoundingBox()).thenReturn(box); return null;});
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            listener.run();listener.run();verify(menu,times(1)).open(player);
            when(world.getBlockAt(-1,66,0)).thenReturn(air);listener.run();
            when(world.getBlockAt(-1,66,0)).thenReturn(portal);listener.run();verify(menu,times(2)).open(player);
            when(games.active(player)).thenReturn(true);listener.run();verify(menu,times(2)).open(player);
            when(games.active(player)).thenReturn(false);when(player.hasPermission("moma.play")).thenReturn(false);listener.run();verify(menu,times(2)).open(player);
            when(player.hasPermission("moma.play")).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(mock(World.class),0,65,0));listener.run();verify(menu,times(2)).open(player);
        }
    }
    @Test void mainHandClickDeduplicatesAndDoesNotReopenOnNextPortalPoll() {
        World world=mock(World.class); Player player=mock(Player.class);
        when(player.getWorld()).thenReturn(world);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0,65,0,.6,66.8,.6));
        when(player.hasPermission("moma.play")).thenReturn(true);
        Block block=mock(Block.class);when(block.getType()).thenReturn(Material.NETHER_PORTAL);when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);
        GameService games=mock(GameService.class);LobbyMenu menu=mock(LobbyMenu.class);
        LobbyPortals listener=new LobbyPortals(mock(MomaPlugin.class),new Lobby(new Location(world,0,65,0),256),games,menu);
        var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(player.teleport(any(Location.class))).thenReturn(true); when(player.isOnline()).thenReturn(true);
        try(var bukkit=mockStatic(Bukkit.class)) {
            var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class),any(Runnable.class),eq(10L))).thenAnswer(call->{var box=player.getBoundingBox(); when(player.getBoundingBox()).thenReturn(new BoundingBox(0,0,0,0,0,0)); call.getArgument(1,Runnable.class).run(); when(player.getBoundingBox()).thenReturn(box); return null;});
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));bukkit.when(Bukkit::getCurrentTick).thenReturn(10);
            listener.interact(event);verifyNoInteractions(menu);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);listener.interact(event);listener.interact(event);listener.run();
            verify(menu,times(1)).open(player);verify(event,times(2)).setCancelled(true);
            var quit=mock(PlayerQuitEvent.class);when(quit.getPlayer()).thenReturn(player);listener.quit(quit);
            listener.run();verify(menu,times(2)).open(player);
        }
    }
}
