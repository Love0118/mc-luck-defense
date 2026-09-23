package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class ReserveMenu {
    private final GameService games;
    private final ShopMenu shop;
    private static final class Holder implements InventoryHolder {
        final UUID owner;final GameSession session;final int page;final List<UUID> units;
        Inventory inventory;boolean consumed;
        Holder(UUID owner,GameSession session,int page,List<UUID> units){this.owner=owner;this.session=session;this.page=page;this.units=units;}
        public Inventory getInventory(){return inventory;}
    }
    ReserveMenu(GameService games,ShopMenu shop){this.games=games;this.shop=shop;}
    void open(Player player,int page) {
        GameSession session=games.session(player);if(session==null || session.arena.ended())return;
        List<Defender> reserve=session.arena.reserveUnits();page=Math.clamp(page,0,Math.max(0,(reserve.size()-1)/45));
        List<Defender> displayed=reserve.subList(page*45,Math.min(reserve.size(),(page+1)*45));
        Holder holder=new Holder(player.getUniqueId(),session,page,displayed.stream().map(Defender::entityId).toList());
        holder.inventory=Bukkit.createInventory(holder,54,Ui.text("&b대기열 &f"+reserve.size()+"/48"));
        for(int i=0;i<displayed.size();i++) {
            Defender d=displayed.get(i);
            ItemStack item=Ui.item(Material.ARMOR_STAND,d.label(),"&f"+d.type().role().label(),"&7좌클릭: 선택 후 배치 칸 클릭",
                    "&7우클릭: 판매 &6+"+d.saleValue()+"골드");
            var meta=item.getItemMeta();meta.displayName(EntityAdapter.rarityName(d.rarity(),"["+d.rarity().label()+"] "+d.label()));item.setItemMeta(meta);
            holder.inventory.setItem(i,item);
        }
        if(page>0)holder.inventory.setItem(45,Ui.item(Material.ARROW,"&e이전"));
        holder.inventory.setItem(46,Ui.item(Material.CHEST,"&b선택한 기물을 대기열로"));
        holder.inventory.setItem(49,Ui.item(Material.BOOK,"&e소환 메뉴"));
        if((page+1)*45<reserve.size())holder.inventory.setItem(53,Ui.item(Material.ARROW,"&e다음"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    boolean click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return false;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || games.session(p)!=h.session
                || h.session.arena.ended() || h.consumed || p.getOpenInventory().getTopInventory()!=h.inventory)return true;
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)return true;
        int slot=event.getRawSlot();
        if(slot>=0 && slot<h.units.size()) {
            UUID id=h.units.get(slot);
            if(h.session.arena.reserveUnits().stream().noneMatch(d->d.entityId().equals(id)))return true;
            h.consumed=true;
            if(event.getClick()==ClickType.RIGHT){games.sell(p,id);open(p,h.page);}
            else {games.select(p,id);p.getInventory().setHeldItemSlot(1);p.closeInventory();}
        } else if(event.getClick()==ClickType.LEFT) {
            if(slot==49){h.consumed=true;shop.open(p);}
            else if(slot==46){h.consumed=true;games.benchSelected(p);open(p,h.page);}
            else if(slot==45 && h.page>0){h.consumed=true;open(p,h.page-1);}
            else if(slot==53 && h.page==0 && h.session.arena.reserveCount()>45){h.consumed=true;open(p,1);}
        }
        return true;
    }
    void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
}
