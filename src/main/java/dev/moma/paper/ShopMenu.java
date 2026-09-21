package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

final class ShopMenu implements Listener {
    private static final int SUMMON = 11, DETAILS = 13, SELL = 15, AUTO_SELL_FIRST = 19,
            SPEED = 8, ODDS = 0, BULK_BUY = 10, AUTO_LAYOUT = 6;
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
                "클릭하여 속도 변경", "1 → 2 → 4 → 8 → 1배"));
        holder.inventory.setItem(ODDS, oddsItem(arena));
        holder.inventory.setItem(4, item(Material.GOLD_INGOT, "&6보유 골드: &e" + Gold.format(arena.coins()), "빈 배치 칸: " + (arena.grid().size() * arena.grid().size() - arena.defenderCount())));
        holder.inventory.setItem(SUMMON, item(Material.EGG, "&a포탑 소환 &7· &6" + arena.summonCost() + "골드", "근접은 가장자리 · 원거리는 안쪽 우선", "클릭하여 소환"));
        boolean buying = holder.session.bulkBuying, layout = holder.session.autoPlacement;
        holder.inventory.setItem(BULK_BUY, item(buying ? Material.BARRIER : Material.DRAGON_EGG,
                buying ? "&e일괄구매 중 &7· &f클릭하여 중지" : "&a일괄구매 &7· &6" + arena.summonCost() + "골드/회",
                buying ? "&e" + holder.session.bulkPurchases + "회 소환" : "골드와 빈 칸이 허용하는 만큼 소환",
                "자동판매로 얻은 골드도 사용합니다."));
        holder.inventory.setItem(AUTO_LAYOUT, item(layout ? Material.LIME_DYE : Material.GRAY_DYE,
                "&b자동 배치 &7· " + (layout ? "&aON" : "&cOFF"),
                "사거리와 공격력에 맞춰 배치", "소환·판매 시 다시 배치", "클릭하여 " + (layout ? "끄기" : "켜기")));
        for (int i = 0; i < SELLABLE.length; i++) {
            Rarity rarity = SELLABLE[i];
            boolean enabled = holder.session.autoSell.contains(rarity);
            String color = "&#" + String.format(Locale.ROOT, "%06x", EntityAdapter.rarityColor(rarity).value());
            holder.inventory.setItem(AUTO_SELL_FIRST+i, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    color + rarity.label() + " &f자동판매 &7· " + (enabled ? "&aON" : "&cOFF"),
                    "마리당 &6+" + rarity.salePrice().getAsInt() + "골드", "켜면 보유·소환한 이 등급을 자동판매",
                    "클릭하여 " + (enabled ? "끄기" : "켜기")));
        }
        Optional<Defender> selected = arena.selected();
        if (selected.isPresent()) {
            Defender d = selected.orElseThrow();
            CombatProfile profile = d.profile();
            String sale = d.rarity().salePrice().isPresent() ? d.saleValue() + "골드" : "판매 불가";
            holder.inventory.setItem(DETAILS, item(Material.PAPER, "&#" + String.format(Locale.ROOT, "%06x", EntityAdapter.rarityColor(d.rarity()).value()) + (d.rarity()==Rarity.TRUE_PRIMORDIAL?"&l":"") + "[" + d.rarity().label() + "] " + d.label(),
                    d.type().role().label(),
                    "강화 +" + d.enhancement() + (d.rarity()==Rarity.TRUE_PRIMORDIAL?"":" · +20 달성 시 다음 등급"),
                    "공격력 " + String.format(Locale.ROOT, "%.1f", profile.damage()),
                    "특성 적용 · 일반 "+String.format(Locale.ROOT,"%.1f",profile.damage()*arena.traits().damageMultiplier(d.type().role(),false))
                            +" / 보스 "+String.format(Locale.ROOT,"%.1f",profile.damage()*arena.traits().damageMultiplier(d.type().role(),true)),
                    "기본 타격 평균 간격 "+String.format(Locale.ROOT,"%.2f",Math.max(1,profile.intervalTicks()/(1+arena.traits().value(TraitCatalog.Family.SPEED)/100.0)))+"틱",
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
    static ItemStack oddsItem() {
        return oddsItem(null);
    }
    static ItemStack oddsItem(Arena arena) {
        boolean openingBonus = arena != null && arena.openingBonusActive();
        SummonTier tier=arena==null?SummonTier.NORMAL:arena.summonTier();
        boolean advanced=tier==SummonTier.ADVANCED;
        var lore = new ArrayList<String>();
        for (Rarity rarity : Rarity.values()) {
            int weight=arena==null?tier.weight(rarity,openingBonus):arena.summonWeight(rarity);
            if(weight==0)continue;
            String color = "&#" + String.format(Locale.ROOT,"%06x",EntityAdapter.rarityColor(rarity).value());
            String percent = java.math.BigDecimal.valueOf(weight).multiply(java.math.BigDecimal.valueOf(100))
                    .divide(java.math.BigDecimal.valueOf(Rarity.TOTAL_WEIGHT)).stripTrailingZeros().toPlainString();
            lore.add(color + rarity.label() + " &f" + percent + "%");
        }
        lore.add("&7포탑 종류: 각 1/" + UnitType.values().length);
        if (openingBonus) {
            lore.add("&a초반 보정 · 최대 " + arena.openingDrawsRemaining() + "회 남음");
            lore.add("&7고대·유물 획득 시 기본 확률로 복귀");
        }
        lore.add(advanced?"&d유물 이상만 등장 · 1회 100골드":"&7101라운드부터 100골드 소환");
        if(arena!=null && arena.openingTraitActive())lore.add("&d인연 보정 · "+arena.traits().openingTarget().label()+" 획득까지 최대 "+arena.openingTraitRemaining()+"회");
        lore.add("&4&l진 태초 &7· 태초 +20 승급 전용");
        lore.add("&7태초·진 태초는 판매할 수 없습니다.");
        return Ui.item(advanced?Material.ENCHANTED_BOOK:Material.KNOWLEDGE_BOOK,
                advanced?"&d&l후반 소환 확률 · 100골드":"&a소환 확률 · 10골드",lore.toArray(String[]::new));
    }
    private ItemStack item(Material material, String title, String... lore) {
        return Ui.item(material, "&6" + title, Arrays.stream(lore).map(s -> "&7" + s).toArray(String[]::new));
    }
    void refreshOpen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                    && !holder.consumed && games.session(player) == holder.session && !holder.arena.ended())
                render(holder);
        }
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) return;
        GameSession session = games.session(player);
        if (session == null || session.arena != holder.arena || session.arena.ended()) return;
        if (event.getClick() != ClickType.LEFT || holder.consumed || Bukkit.getCurrentTick() < nextClick.getOrDefault(holder.owner, 0)) return;
        int slot = event.getRawSlot();
        if (slot != SUMMON && slot != SELL && slot != SPEED && slot != BULK_BUY && slot != AUTO_LAYOUT
                && (slot < AUTO_SELL_FIRST || slot >= AUTO_SELL_FIRST+SELLABLE.length)) return;
        holder.consumed = true;
        nextClick.put(holder.owner, Bukkit.getCurrentTick() + 1);
        if (slot == SUMMON) games.summon(player);
        else if (slot == SELL) games.sell(player);
        else if (slot == SPEED) games.speed(player, session.speed() == 8 ? 1 : session.speed() * 2);
        else if (slot == BULK_BUY) games.toggleBulkBuy(player);
        else if (slot == AUTO_LAYOUT) games.toggleAutoPlacement(player);
        else games.toggleAutoSell(player, SELLABLE[slot-AUTO_SELL_FIRST]);
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
