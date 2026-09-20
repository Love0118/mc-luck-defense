package dev.moma.paper;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import java.util.*;

final class GameListener implements Listener {
    private final GameService games;
    private final ArenaMaps maps;
    private final ShopMenu shop;
    private final Map<UUID, Integer> clicks = new HashMap<>();

    GameListener(GameService games, ArenaMaps maps, ShopMenu shop) { this.games = games; this.maps = maps; this.shop = shop; }
    @EventHandler public void swap(PlayerSwapHandItemsEvent event) {
        if (!games.active(event.getPlayer())) return;
        event.setCancelled(true); if (games.playing(event.getPlayer())) shop.open(event.getPlayer());
    }
    private boolean beginClick(Player player) {
        if (!games.active(player) || clicks.getOrDefault(player.getUniqueId(), -1) == Bukkit.getCurrentTick()) return false;
        clicks.put(player.getUniqueId(), Bukkit.getCurrentTick());
        return true;
    }
    private void leftClick(Player player) {
        if (!games.usingMoveTool(player) || !beginClick(player)) return;
        // A shared ray makes left-click air work beyond vanilla melee reach; blocks occlude entities.
        RayTraceResult hit = player.getWorld().rayTrace(player.getEyeLocation(), player.getEyeLocation().getDirection(),
                24, FluidCollisionMode.NEVER, true, 0.15, e -> games.entities.managed(e));
        if (hit == null) { games.move(player, null); return; }
        if (hit.getHitEntity() != null) games.select(player, hit.getHitEntity().getUniqueId());
        else games.move(player, hit.getHitBlock());
    }
    @EventHandler public void swing(PlayerArmSwingEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) leftClick(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void attack(PrePlayerAttackEntityEvent event) {
        if (games.entities.managed(event.getAttacked()) || games.active(event.getPlayer())) {
            event.setCancelled(true);
            if (games.entities.managed(event.getAttacked()) && games.usingMoveTool(event.getPlayer()) && beginClick(event.getPlayer())) games.select(event.getPlayer(), event.getAttacked().getUniqueId());
        }
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if (!games.active(event.getPlayer())) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR)) leftClick(event.getPlayer());
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) sellTool(event.getPlayer(),event.getHand());
    }
    @EventHandler public void entityInteract(PlayerInteractEntityEvent event) {
        if (games.entities.managed(event.getRightClicked()) || games.active(event.getPlayer())) event.setCancelled(true);
        sellTool(event.getPlayer(),event.getHand());
    }
    @EventHandler public void entityInteractAt(PlayerInteractAtEntityEvent event) {
        if (games.entities.managed(event.getRightClicked()) || games.active(event.getPlayer())) event.setCancelled(true);
        sellTool(event.getPlayer(),event.getHand());
    }
    private void sellTool(Player player,EquipmentSlot hand) {
        if (hand==EquipmentSlot.HAND && games.usingBgmTool(player) && beginClick(player)) {
            games.useBgm(player); return;
        }
        if (hand==EquipmentSlot.HAND && games.usingSoundTool(player) && beginClick(player)) {
            games.tools.cycleSound(player); Ui.sound(player, Ui.Cue.CLICK); return;
        }
        if (hand==EquipmentSlot.HAND && games.usingLeaveTool(player) && beginClick(player)) {
            Ui.sound(player, Ui.Cue.CLICK); games.leave(player); return;
        }
        if (hand==EquipmentSlot.HAND && games.usingSellTool(player) && beginClick(player)) games.sell(player);
    }
    @EventHandler public void inventory(org.bukkit.event.inventory.InventoryClickEvent event) {
        if(event.getWhoClicked() instanceof Player player && games.active(player))event.setCancelled(true);
    }
    @EventHandler public void inventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if(event.getWhoClicked() instanceof Player player && games.active(player))event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent event) { games.tools.restore(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void damage(EntityDamageEvent event) {
        if (games.entities.managed(event.getEntity()) || event.getEntity() instanceof Player p && games.active(p)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void damageByEntity(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        if (games.entities.managed(source) || source instanceof Player p && games.active(p)) event.setCancelled(true);
    }
    @EventHandler public void target(EntityTargetEvent event) { if (games.entities.managed(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void combust(EntityCombustEvent event) { if (games.entities.managed(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void transform(EntityTransformEvent event) { if (games.entities.managed(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void split(SlimeSplitEvent event) { if (games.entities.managed(event.getEntity())) event.setCancelled(true); }
    @EventHandler public void changeBlock(EntityChangeBlockEvent event) { if (games.entities.managed(event.getEntity()) || maps.contains(event.getBlock().getLocation())) event.setCancelled(true); }
    @EventHandler public void entityTeleport(EntityTeleportEvent event) {
        if (games.entities.managed(event.getEntity()) && !games.entities.moving(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler public void death(EntityDeathEvent event) {
        if (games.entities.managed(event.getEntity())) { event.getDrops().clear(); event.setDroppedExp(0); }
        else if(event.getEntity() instanceof Player player && games.active(player))event.getDrops().removeIf(games.tools::isTool);
    }
    @EventHandler public void breakBlock(BlockBreakEvent event) {
        if (games.active(event.getPlayer()) || maps.contains(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void placeBlock(BlockPlaceEvent event) {
        if (games.active(event.getPlayer()) || maps.contains(event.getBlock().getLocation())) event.setCancelled(true);
    }
    @EventHandler public void entityExplosion(EntityExplodeEvent event) {
        if(games.entities.managed(event.getEntity()))event.setCancelled(true);
        event.blockList().removeIf(b -> maps.contains(b.getLocation()));
    }
    @EventHandler public void blockExplosion(BlockExplodeEvent event) { event.blockList().removeIf(b -> maps.contains(b.getLocation())); }
    @EventHandler public void food(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && games.active(player)) event.setCancelled(true);
    }
    @EventHandler public void drop(PlayerDropItemEvent event) { if (games.active(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent event) {
        if (games.entities.managed(event.getEntity()) || event.getEntity() instanceof Player p && games.active(p)) event.setCancelled(true);
    }
    @EventHandler public void spawn(CreatureSpawnEvent event) {
        if (maps.contains(event.getLocation()) && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) event.setCancelled(true);
    }
    @EventHandler public void load(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) if (games.entities.managed(entity) && !games.tracks(entity.getUniqueId())) entity.remove();
    }
    @EventHandler public void quit(PlayerQuitEvent event) { clicks.remove(event.getPlayer().getUniqueId()); games.disconnect(event.getPlayer()); }
    @EventHandler public void teleport(PlayerTeleportEvent event) {
        GameSession session = games.session(event.getPlayer());
        if (session != null && event.getTo() != null && !session.map.contains(event.getTo())) event.setCancelled(true);
    }
}
