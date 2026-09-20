package dev.moma.paper;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;

/** Opening is edge-triggered: closing the menu inside a portal never traps the player. */
final class LobbyPortals implements Listener, Runnable {
    static final long MENU_DELAY_TICKS = 10;
    private final Lobby lobby;
    private final GameService games;
    private final LobbyMenu menu;
    private final MomaPlugin plugin;
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> inside = new HashSet<>();
    private final Map<UUID, Integer> clicked = new HashMap<>();
    LobbyPortals(MomaPlugin plugin, Lobby lobby, GameService games, LobbyMenu menu) {
        this.plugin = plugin; this.lobby = lobby; this.games = games; this.menu = menu;
    }
    private boolean eligible(Player player) {
        return lobby.contains(player.getLocation()) && !games.active(player) && player.hasPermission("moma.play");
    }
    @Override public void run() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId(); online.add(id);
            if (!eligible(player) || !touchesPortal(player)) { inside.remove(id); continue; }
            if (inside.add(id)) openFromSpawn(player);
        }
        inside.retainAll(online); clicked.keySet().retainAll(online);
    }
    private void openFromSpawn(Player player) {
        UUID id=player.getUniqueId();
        if (!pending.add(id)) return;
        if (!player.teleport(lobby.spawn())) { pending.remove(id); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pending.remove(id) || !player.isOnline() || !eligible(player) || touchesPortal(player)) return;
            menu.open(player);
        }, MENU_DELAY_TICKS);
    }
    static boolean touchesPortal(Player player) {
        BoundingBox box = player.getBoundingBox();
        for (int x = (int)Math.floor(box.getMinX()); x <= (int)Math.floor(Math.nextDown(box.getMaxX())); x++)
            for (int y = (int)Math.floor(box.getMinY()); y <= (int)Math.floor(Math.nextDown(box.getMaxY())); y++)
                for (int z = (int)Math.floor(box.getMinZ()); z <= (int)Math.floor(Math.nextDown(box.getMaxZ())); z++)
                    if (player.getWorld().getBlockAt(x,y,z).getType() == Material.NETHER_PORTAL) return true;
        return false;
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player) || event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.NETHER_PORTAL) return;
        event.setCancelled(true);
        int tick = Bukkit.getCurrentTick();
        if (clicked.getOrDefault(player.getUniqueId(), -1) == tick) return;
        clicked.put(player.getUniqueId(), tick);
        if (touchesPortal(player)) inside.add(player.getUniqueId());
        openFromSpawn(player);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId()); clicked.remove(event.getPlayer().getUniqueId()); pending.remove(event.getPlayer().getUniqueId());
    }
}
