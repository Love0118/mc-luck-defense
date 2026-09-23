package dev.moma.paper;

import java.util.UUID;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class SpeedMenu {
    private static final int[] SPEEDS={1,2,4,8,16,32};
    private final GameService games;
    private final ShopMenu shop;
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final GameSession session;
        Inventory inventory;
        boolean consumed;
        Holder(Player player,GameSession session){owner=player.getUniqueId();this.session=session;}
        @Override public Inventory getInventory(){return inventory;}
    }
    SpeedMenu(GameService games,ShopMenu shop){this.games=games;this.shop=shop;}
    void open(Player player) {
        GameSession session=games.session(player);
        if(session==null || session.arena.ended())return;
        Holder holder=new Holder(player,session);
        holder.inventory=Bukkit.createInventory(holder,27,Ui.text("&b게임 배속 선택"));
        for(int i=0;i<SPEEDS.length;i++) {
            int speed=SPEEDS[i];boolean selected=session.speed()==speed;
            holder.inventory.setItem(10+i,Ui.item(selected?Material.LIME_DYE:Material.CLOCK,
                    (selected?"&a":"&e")+speed+"배",selected?"&a현재 속도":"&7클릭하여 선택"));
        }
        holder.inventory.setItem(22,Ui.item(Material.ARROW,"&e돌아가기"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    boolean click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return false;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || games.session(player)!=holder.session || holder.session.arena.ended() || holder.consumed
                || player.getOpenInventory().getTopInventory()!=holder.inventory || event.getClick()!=ClickType.LEFT)return true;
        int slot=event.getRawSlot();
        if(slot==22){holder.consumed=true;shop.open(player);}
        else if(slot>=10 && slot<10+SPEEDS.length) {
            holder.consumed=true;games.speed(player,SPEEDS[slot-10]);shop.open(player);
        }
        return true;
    }
    void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    void close(InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof Holder holder)holder.consumed=true;
    }
}
