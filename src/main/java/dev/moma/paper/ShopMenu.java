package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

final class ShopMenu implements Listener {
    private static final int SUMMON = 11, DETAILS = 13, SELL = 15, BULK_FIRST = 20, SPEED = 8;
    private static final Rarity[] SELLABLE = Arrays.stream(Rarity.values()).filter(r -> r.salePrice().isPresent()).toArray(Rarity[]::new);
    private final MomaPlugin plugin;
    private final GameService games;
    private final Map<UUID, Integer> nextClick = new HashMap<>();

    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final Arena arena;
        final GameSession session;
        Inventory inventory;
        boolean consumed;
        Holder(UUID owner, GameSession session) { this.owner = owner; this.session = session; this.arena = session.arena; }
        @Override public Inventory getInventory() { return inventory; }
    }
    ShopMenu(MomaPlugin plugin, GameService games) { this.plugin = plugin; this.games = games; }
    void open(Player player) {
        GameSession session = games.session(player);
        if (session == null || session.arena.ended()) return;
        var holder = new Holder(player.getUniqueId(), session);
        holder.inventory = Bukkit.createInventory(holder, 27, Ui.text("&6운빨 디펜스 &8· &e소환과 판매"));
        render(holder);
        player.openInventory(holder.inventory);
        Ui.sound(player,Ui.Cue.OPEN);
    }
    private void render(Holder holder) {
        Arena arena = holder.arena;
        holder.inventory.clear();
        holder.inventory.setItem(SPEED, item(Material.CLOCK, "&b게임 배속: &e" + holder.session.speed() + "배",
                "좌클릭: 1 → 2 → 4 → 8 → 1배", "자신의 세션에만 적용", "웨이브·이동·공격·감속을 함께 가속"));
        holder.inventory.setItem(4, item(Material.GOLD_INGOT, "&6보유 재화: &e" + arena.coins() + "원", "빈 배치 칸: " + (arena.grid().size() * arena.grid().size() - arena.defenderCount())));
        holder.inventory.setItem(SUMMON, item(Material.EGG, "&a무작위 소환 &7· &610원", "24종 × 9개 등급 독립 추첨", "근접은 가장자리 · 원거리는 안쪽 우선", "우선 영역이 차면 남은 칸 사용", "좌클릭으로 1회 소환"));
        for (int i = 0; i < SELLABLE.length; i++) {
            Rarity rarity = SELLABLE[i];
            long count = arena.activeDefenders().stream().filter(d -> d.rarity() == rarity).count();
            String color = "&#" + String.format(Locale.ROOT, "%06x", EntityAdapter.rarityColor(rarity).value());
            holder.inventory.setItem(BULK_FIRST+i, item(Material.EMERALD, color + rarity.label() + " &f일괄판매",
                    "&e" + count + "마리 &7· &6+" + count*rarity.salePrice().getAsInt() + "원", "이 등급만 전부 판매합니다.", "전설 이상은 판매할 수 없습니다."));
        }
        Optional<Defender> selected = arena.selected();
        if (selected.isPresent()) {
            Defender d = selected.orElseThrow();
            CombatProfile profile = d.type().profile().at(d.rarity());
            String sale = d.rarity().salePrice().isPresent() ? d.rarity().salePrice().getAsInt() + "원" : "판매 불가";
            holder.inventory.setItem(DETAILS, item(Material.PAPER, "&#" + String.format(Locale.ROOT, "%06x", EntityAdapter.rarityColor(d.rarity()).value()) + "[" + d.rarity().label() + "] " + d.type().label(),
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
        return Ui.item(material, "&6" + title, Arrays.stream(lore).map(s -> "&7" + s).toArray(String[]::new));
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) return;
        GameSession session = games.session(player);
        if (session == null || session.arena != holder.arena || session.arena.ended()) return;
        if (event.getClick() != ClickType.LEFT || holder.consumed || Bukkit.getCurrentTick() < nextClick.getOrDefault(holder.owner, 0)) return;
        int slot = event.getRawSlot();
        if (slot != SUMMON && slot != SELL && slot != SPEED && (slot < BULK_FIRST || slot >= BULK_FIRST+SELLABLE.length)) return;
        holder.consumed = true;
        nextClick.put(holder.owner, Bukkit.getCurrentTick() + 1);
        if (slot == SUMMON) games.summon(player);
        else if (slot == SELL) games.sell(player);
        else if (slot == SPEED) games.speed(player, session.speed() == 8 ? 1 : session.speed() * 2);
        else games.sellRarity(player, SELLABLE[slot-BULK_FIRST]);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (games.session(player) == session && player.getOpenInventory().getTopInventory() == holder.inventory) {
                render(holder); holder.consumed = false;
            }
        }, 1);
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) holder.consumed = true;
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { nextClick.remove(event.getPlayer().getUniqueId()); }
}
