package dev.moma.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class LobbyMenu implements Listener {
    private final MomaPlugin plugin;
    private final GameService games;
    private final ArenaMaps maps;
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final List<String> arenas;
        Inventory inventory;
        boolean consumed;
        Holder(UUID owner, List<String> arenas) { this.owner = owner; this.arenas = arenas; }
        @Override public Inventory getInventory() { return inventory; }
    }
    LobbyMenu(MomaPlugin plugin, GameService games, ArenaMaps maps) { this.plugin = plugin; this.games = games; this.maps = maps; }
    void open(Player player) {
        if (!player.hasPermission("moma.play") || games.playing(player)) return;
        var holder = new Holder(player.getUniqueId(), maps.all().stream().limit(20).map(ArenaMap::id).toList());
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("MC Luck Defense · 게임 시작"));
        for (int slot = 0; slot < holder.arenas.size(); slot++) {
            String id = holder.arenas.get(slot); boolean free = games.available(id);
            holder.inventory.setItem(slot, item(free ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE, id + (free ? " · 입장 가능" : " · 사용 불가")));
        }
        holder.inventory.setItem(22, item(Material.NETHER_STAR, "빠른 시작 · 빈 개인 전장 자동 배정"));
        player.openInventory(holder.inventory);
    }
    private ItemStack item(Material material, String name) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD)); item.setItemMeta(meta); return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || holder.consumed || event.getClick() != ClickType.LEFT || !player.hasPermission("moma.play") || games.playing(player)) return;
        int slot = event.getRawSlot();
        if (slot != 22 && (slot < 0 || slot >= holder.arenas.size())) return;
        holder.consumed = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.hasPermission("moma.play") || games.playing(player)
                    || player.getOpenInventory().getTopInventory() != holder.inventory) return;
            player.closeInventory();
            try { if (slot == 22) games.start(player); else games.join(player, holder.arenas.get(slot)); }
            catch (IllegalArgumentException error) { player.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED)); }
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) holder.consumed = true;
    }
}
