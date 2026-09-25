package dev.moma.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

final class LobbyListener implements Listener {
    private final Lobby lobby;
    private final GameService games;
    private final LobbyMenu menu;
    LobbyListener(Lobby lobby, GameService games, LobbyMenu menu) { this.lobby = lobby; this.games = games; this.menu = menu; }
    @EventHandler public void join(PlayerJoinEvent event) {
        lobby.send(event.getPlayer());
        event.getPlayer().sendMessage(Component.text("MC Luck Defense · F 또는 /mud로 시작 메뉴 · /mud start로 바로 시작"));
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        games.disconnect(event.getPlayer()); event.setRespawnLocation(lobby.spawn());
        lobby.prepare(event.getPlayer());
    }
    @EventHandler public void swap(PlayerSwapHandItemsEvent event) {
        if (games.watching(event.getPlayer()) || !games.playing(event.getPlayer()) && lobby.contains(event.getPlayer().getLocation())) { event.setCancelled(true); menu.open(event.getPlayer()); }
    }
    @EventHandler public void move(PlayerMoveEvent event) {
        if (!games.playing(event.getPlayer()) && lobby.contains(event.getTo()) && lobby.outside(event.getTo())) {
            event.getPlayer().setFallDistance(0); event.setTo(lobby.spawn());
        }
    }
    @EventHandler public void damage(EntityDamageEvent event) {
        if (lobby.contains(event.getEntity().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void food(FoodLevelChangeEvent event) {
        if (lobby.contains(event.getEntity().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void breakBlock(BlockBreakEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void placeBlock(BlockPlaceEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void interact(PlayerInteractEvent event) {
        boolean viewer=games.watching(event.getPlayer());
        if (!viewer && !lobby.contains(event.getPlayer().getLocation())) return;
        event.setCancelled(true);
        if ((viewer || !games.active(event.getPlayer())) && event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && games.tools.holding(event.getPlayer(), "sessions")) menu.open(event.getPlayer());
        if (!viewer && !games.active(event.getPlayer()) && event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && games.tools.holding(event.getPlayer(), "summon_alerts")) {
            games.tools.toggleSummonAlerts(event.getPlayer()); Ui.sound(event.getPlayer(),Ui.Cue.CLICK);
        }
    }
    @EventHandler public void inventory(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (lobby.contains(event.getWhoClicked().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void drag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (lobby.contains(event.getWhoClicked().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void interactEntity(PlayerInteractEntityEvent event) { if (lobby.contains(event.getPlayer().getLocation())) event.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent event) { if (lobby.contains(event.getPlayer().getLocation())) event.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent event) { if (lobby.contains(event.getEntity().getLocation())) event.setCancelled(true); }
    @EventHandler public void portal(PlayerPortalEvent event) { if (lobby.contains(event.getFrom())) event.setCancelled(true); }
    @EventHandler public void entityExplosion(EntityExplodeEvent event) { event.blockList().removeIf(b -> lobby.contains(b.getLocation())); }
    @EventHandler public void blockExplosion(BlockExplodeEvent event) { event.blockList().removeIf(b -> lobby.contains(b.getLocation())); }
    @EventHandler public void ignite(BlockIgniteEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void fade(BlockFadeEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void flow(BlockFromToEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void changeBlock(EntityChangeBlockEvent event) { if (lobby.contains(event.getBlock().getLocation())) event.setCancelled(true); }
}
