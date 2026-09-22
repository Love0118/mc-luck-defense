package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;

final class TraitMenu implements Listener {
    private final GameService games;
    private final Lobby lobby;
    private static final int PAGE_SIZE=36;
    private static final class Holder implements InventoryHolder {
        final UUID owner;final int page;final List<TraitCatalog.Entry> entries;
        Inventory inventory;
        Holder(UUID owner,int page,List<TraitCatalog.Entry> entries){this.owner=owner;this.page=page;this.entries=entries;}
        @Override public Inventory getInventory(){return inventory;}
    }
    TraitMenu(GameService games,Lobby lobby){this.games=games;this.lobby=lobby;}
    private boolean eligible(Player player) {
        return !games.active(player) && player.hasPermission("moma.play") && lobby!=null && lobby.contains(player.getLocation());
    }
    void open(Player player){open(player,0);}
    private void open(Player player,int requested) {
        if(!eligible(player))return;
        int pages=(TraitCatalog.ALL.size()+PAGE_SIZE-1)/PAGE_SIZE,page=Math.clamp(requested,0,pages-1);
        var holder=new Holder(player.getUniqueId(),page,TraitCatalog.ALL.subList(page*PAGE_SIZE,Math.min((page+1)*PAGE_SIZE,TraitCatalog.ALL.size())));
        holder.inventory=Bukkit.createInventory(holder,54,Ui.text("&d특성 선택 &8· "+(page+1)+"/"+pages));
        var data=player.getPersistentDataContainer();var selected=TraitSelections.load(data);
        for(int i=0;i<holder.entries.size();i++) {
            var entry=holder.entries.get(i);boolean unlocked=TraitSelections.unlocked(data,entry),equipped=selected.ids().contains(entry.id());
            var lore=new ArrayList<String>();lore.add("&f"+entry.description());
            lore.add("&7업적: "+entry.achievement().title());lore.add("&7"+entry.achievement().description());
            lore.add("&7진행: "+AchievementStats.get(data,entry.achievement().metric())+"/"+entry.achievement().target());
            if(entry.passive()) {
                boolean active=selected.passives().stream().anyMatch(e->e.id().equals(entry.id()));
                lore.add(!unlocked?"&c업적 달성 시 해금":active?"&a패시브 · 자동 적용":"&7상위 패시브 적용 중");
            } else lore.add(equipped?"&e클릭: 해제":unlocked?"&a클릭: 장착":"&c업적 달성 시 해금");
            holder.inventory.setItem(i,Ui.item(!unlocked?Material.GRAY_DYE:equipped?Material.LIME_DYE:Material.ENCHANTED_BOOK,
                    (equipped?"&a":unlocked?"&d":"&7")+entry.name(),lore.toArray(String[]::new)));
        }
        int slots=TraitCatalog.slots(TraitSelections.highest(data));
        for(int i=0;i<3;i++) {
            var entry=i<selected.entries().size()?selected.entries().get(i):null;
            holder.inventory.setItem(45+i,Ui.item(i>=slots?Material.BARRIER:entry==null?Material.LIGHT_GRAY_DYE:Material.NETHER_STAR,
                    "&e특성 "+(i+1)+" &7· "+(i>=slots?"잠김":entry==null?"빈 슬롯":entry.name()),
                    i>=slots?"&7"+new int[]{100,250,500}[i]+"라운드 도달 시 해금":entry==null?"&7위 목록에서 선택하세요.":"&7클릭: 해제"));
        }
        holder.inventory.setItem(49,Ui.item(Material.PAPER,"&d장착 "+selected.entries().size()+"/"+slots,
                "&7같은 계열은 하나만 장착","&7선택한 특성은 다음 게임 시작부터 적용"));
        if(page>0)holder.inventory.setItem(51,Ui.item(Material.ARROW,"&e이전 페이지"));
        if(page+1<pages)holder.inventory.setItem(53,Ui.item(Material.ARROW,"&e다음 페이지"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        Player player=event.getPlayer();
        if(eligible(player) && games.tools.holding(player,"traits")){event.setCancelled(true);open(player);}
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !eligible(player) || event.getClick()!=ClickType.LEFT)return;
        // A queued click from an inventory that was already replaced must not toggle twice.
        if(player.getOpenInventory().getTopInventory()!=holder.inventory)return;
        int slot=event.getRawSlot();
        if(slot==51 && holder.page>0){open(player,holder.page-1);return;}
        if(slot==53 && (holder.page+1)*PAGE_SIZE<TraitCatalog.ALL.size()){open(player,holder.page+1);return;}
        var data=player.getPersistentDataContainer();var ids=new ArrayList<>(TraitSelections.load(data).ids());
        try {
            if(slot>=0 && slot<holder.entries.size()) {
                var entry=holder.entries.get(slot);
                if(!TraitSelections.unlocked(data,entry))throw new IllegalArgumentException("업적을 먼저 달성하세요.");
                if(entry.passive()){Ui.sound(player,Ui.Cue.CLICK);open(player,holder.page);return;}
                if(!ids.remove(entry.id())) {
                    ids.removeIf(id->TraitCatalog.find(id).family()==entry.family());ids.add(entry.id());
                }
            } else if(slot>=45 && slot<48 && slot-45<ids.size())ids.remove(slot-45);
            else return;
            TraitSelections.save(data,ids);Ui.sound(player,Ui.Cue.CLICK);open(player,holder.page);
        } catch(IllegalArgumentException error){player.sendMessage(Ui.text("&c"+error.getMessage()));Ui.sound(player,Ui.Cue.ERROR);}
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
}
