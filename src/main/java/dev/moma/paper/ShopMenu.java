package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

final class ShopMenu implements Listener {
    private static final int SUMMON = 11, DETAILS = 13, SELL = 15;
    private final MomaPlugin plugin;
    private final GameService games;
    private final Map<UUID, Integer> nextClick = new HashMap<>();

    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final Arena arena;
        Inventory inventory;
        boolean consumed;
        Holder(UUID owner, Arena arena) { this.owner = owner; this.arena = arena; }
        @Override public Inventory getInventory() { return inventory; }
    }
    ShopMenu(MomaPlugin plugin, GameService games) { this.plugin = plugin; this.games = games; }
    void open(Player player) {
        GameSession session = games.session(player);
        if (session == null || session.arena.ended()) return;
        var holder = new Holder(player.getUniqueId(), session.arena);
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("운빨 디펜스 · 소환과 판매"));
        render(holder);
        player.openInventory(holder.inventory);
    }
    private void render(Holder holder) {
        Arena arena = holder.arena;
        holder.inventory.clear();
        holder.inventory.setItem(4, item(Material.GOLD_INGOT, "보유 재화: " + arena.coins() + "원", "빈 배치 칸: " + (arena.grid().size() * arena.grid().size() - arena.defenders().size())));
        holder.inventory.setItem(SUMMON, item(Material.EGG, "무작위 소환 · 10원", "24종 × 9개 등급 독립 추첨", "바깥줄부터 자동 배치", "좌클릭으로 1회 소환"));
        Optional<Defender> selected = arena.selected();
        if (selected.isPresent()) {
            Defender d = selected.orElseThrow();
            CombatProfile profile = d.type().profile().at(d.rarity());
            String sale = d.rarity().salePrice().isPresent() ? d.rarity().salePrice().getAsInt() + "원" : "판매 불가";
            holder.inventory.setItem(DETAILS, item(Material.PAPER, "[" + d.rarity().label() + "] " + d.type().label(),
                    d.type().role().label(),
                    "공격력 " + String.format(Locale.ROOT, "%.1f", profile.damage()) + " · 간격 " + profile.intervalTicks() + "틱",
                    "사거리 " + String.format(Locale.ROOT, "%.1f", profile.range()),
                    d.rarity().abilityLevel() == 0 ? "특수효과: 전설부터 해금" : d.type().role().ability() + " · " + d.rarity().abilityLevel() + "단계",
                    "판매: " + sale));
            holder.inventory.setItem(SELL, item(d.rarity().salePrice().isPresent() ? Material.EMERALD : Material.BARRIER,
                    "선택 포탑 판매 · " + sale, "판매 후 해당 배치 칸이 비워집니다."));
        } else {
            holder.inventory.setItem(DETAILS, item(Material.PAPER, "선택한 포탑 없음", "GUI를 닫고 자신의 포탑을 좌클릭하세요."));
            holder.inventory.setItem(SELL, item(Material.BARRIER, "판매할 포탑을 선택하세요"));
        }
    }
    private ItemStack item(Material material, String title, String... lore) {
        var item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD));
        meta.lore(Arrays.stream(lore).map(s -> Component.text(s, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) return;
        GameSession session = games.session(player);
        if (session == null || session.arena != holder.arena || session.arena.ended()) return;
        if (event.getClick() != ClickType.LEFT || holder.consumed || Bukkit.getCurrentTick() < nextClick.getOrDefault(holder.owner, 0)) return;
        int slot = event.getRawSlot();
        if (slot != SUMMON && slot != SELL) return;
        holder.consumed = true;
        nextClick.put(holder.owner, Bukkit.getCurrentTick() + 5);
        if (slot == SUMMON) games.summon(player); else games.sell(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (games.session(player) == session && player.getOpenInventory().getTopInventory() == holder.inventory) {
                render(holder); holder.consumed = false;
            }
        }, 5);
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) holder.consumed = true;
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { nextClick.remove(event.getPlayer().getUniqueId()); }
}
