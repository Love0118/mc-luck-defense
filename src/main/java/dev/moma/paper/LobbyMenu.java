package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class LobbyMenu implements Listener {
    static final int PAGE_SIZE = 45, PREVIOUS = 45, JOIN = 49, LEAVE = 50, NEXT = 53;
    private final MomaPlugin plugin;
    private final GameService games;
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final List<GameService.SessionInfo> sessions;
        final int page, pages;
        Inventory inventory;
        boolean consumed;
        Holder(UUID owner, List<GameService.SessionInfo> sessions, int page, int pages) {
            this.owner = owner; this.sessions = sessions; this.page = page; this.pages = pages;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    LobbyMenu(MomaPlugin plugin, GameService games) { this.plugin = plugin; this.games = games; }
    void open(Player player) { open(player, 0); }
    private void open(Player player, int requestedPage) {
        if (!player.hasPermission("moma.play") || games.playing(player)) return;
        List<GameService.SessionInfo> active = games.activeSessions();
        int pages = Math.max(1, (active.size()+PAGE_SIZE-1)/PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages-1));
        var holder = new Holder(player.getUniqueId(), active.subList(page*PAGE_SIZE, Math.min(active.size(), (page+1)*PAGE_SIZE)), page, pages);
        holder.inventory = Bukkit.createInventory(holder, 54, Ui.text("&6게임 참가 &8/ &b활성 세션 관전 &8["+(page+1)+"/"+pages+"]"));
        for (int slot = 0; slot < holder.sessions.size(); slot++) {
            var session = holder.sessions.get(slot);
            holder.inventory.setItem(slot, sessionItem(session));
        }
        if (active.isEmpty()) holder.inventory.setItem(22, Ui.item(Material.GRAY_DYE, "&7관전 가능한 게임이 없습니다."));
        holder.inventory.setItem(JOIN, Ui.item(Material.NETHER_STAR, "&a&l게임 참가", "&730골드로 시작", "&7최고 라운드에 도전하세요."));
        if (games.watching(player)) holder.inventory.setItem(LEAVE, Ui.item(Material.OAK_DOOR, "&e관전 종료 · 로비로"));
        if (page > 0) holder.inventory.setItem(PREVIOUS, Ui.item(Material.ARROW, "&e이전 페이지"));
        if (page+1 < pages) holder.inventory.setItem(NEXT, Ui.item(Material.ARROW, "&e다음 페이지"));
        player.openInventory(holder.inventory);
        Ui.sound(player,Ui.Cue.OPEN);
    }
    private ItemStack sessionItem(GameService.SessionInfo session) {
        String song=games.bgm==null?"재생 안 함":games.bgm.nowPlaying(session.sessionId());
        ItemStack item=Ui.item(Material.ENDER_EYE,"&b"+session.playerName()+" &f관전");
        var meta=item.getItemMeta();
        var lore=new ArrayList<net.kyori.adventure.text.Component>();
        lore.add(Ui.text("&7전장 &f"+session.arena()));
        lore.add(Ui.text(session.round()==0?"&e시작 준비 중":"&7진행 라운드 &e"+session.round()));
        lore.add(Ui.text("&7남은 적 &c"+session.enemies()));
        lore.add(Ui.text("&7재생 중인 곡 &f").append(net.kyori.adventure.text.Component.text(song)));
        lore.add(Ui.text("&7클릭하면 이 세션을 관전합니다."));
        meta.lore(lore);item.setItemMeta(meta);return item;
    }
    void refreshOpen() {
        Map<UUID,GameService.SessionInfo> current=null;
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) || holder.consumed)continue;
            if(current==null) {
                current=new HashMap<>();
                for(var session:games.activeSessions())current.put(session.sessionId(),session);
            }
            for(int slot=0;slot<holder.sessions.size();slot++) {
                var session=current.get(holder.sessions.get(slot).sessionId());
                holder.inventory.setItem(slot,session==null?Ui.item(Material.BARRIER,"&7종료된 세션"):sessionItem(session));
            }
        }
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || holder.consumed || event.getClick() != ClickType.LEFT || !player.hasPermission("moma.play") || games.playing(player)) return;
        int slot = event.getRawSlot();
        boolean previous = slot == PREVIOUS && holder.page > 0, next = slot == NEXT && holder.page+1 < holder.pages;
        if (slot != JOIN && !(slot == LEAVE && games.watching(player)) && !previous && !next && (slot < 0 || slot >= holder.sessions.size())) return;
        holder.consumed = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.hasPermission("moma.play") || games.playing(player)
                    || player.getOpenInventory().getTopInventory() != holder.inventory) return;
            player.closeInventory();
            try {
                if (previous || next) open(player, holder.page + (next ? 1 : -1));
                else if (slot == JOIN) games.start(player);
                else if (slot == LEAVE) games.leave(player);
                else games.spectate(player, holder.sessions.get(slot).sessionId());
                if (!previous && !next) Ui.sound(player,Ui.Cue.CLICK);
            } catch (IllegalArgumentException error) {
                player.sendMessage(Ui.text("&c" + error.getMessage())); Ui.sound(player,Ui.Cue.ERROR);
            }
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) holder.consumed = true;
    }
}
