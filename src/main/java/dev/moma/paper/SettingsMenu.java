package dev.moma.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

final class SettingsMenu implements Listener {
    private final SessionTools tools;
    private final Map<UUID,Integer> openedAt=new HashMap<>();
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;
        int lastClick=Integer.MIN_VALUE;
        boolean closed;
        Holder(Player player){owner=player.getUniqueId();}
        @Override public Inventory getInventory(){return inventory;}
    }
    SettingsMenu(SessionTools tools){this.tools=tools;}
    void open(Player player) {
        Holder holder=new Holder(player);
        holder.inventory=Bukkit.createInventory(holder,27,Ui.text("&e환경설정"));
        render(player,holder);player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    private void render(Player player,Holder holder) {
        var options=NotificationPreferences.values();
        for(int i=0;i<options.length;i++) {
            boolean enabled=options[i].enabled(player);
            holder.inventory.setItem(11+i*2,Ui.item(enabled?Material.LIME_DYE:Material.GRAY_DYE,
                    "&e"+options[i].label+" &7· "+(enabled?"&aON":"&7OFF"),"&7클릭하여 켜기·끄기"));
        }
        holder.inventory.setItem(22,Ui.item(Material.BARRIER,"&c닫기"));
    }
    private boolean use(Player player,EquipmentSlot hand) {
        if(hand!=EquipmentSlot.HAND || !tools.holding(player,"summon_alerts"))return false;
        int tick=Bukkit.getCurrentTick();
        if(!Objects.equals(openedAt.put(player.getUniqueId(),tick),tick))open(player);
        return true;
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if((event.getAction()==Action.RIGHT_CLICK_AIR || event.getAction()==Action.RIGHT_CLICK_BLOCK)
                && use(event.getPlayer(),event.getHand()))event.setCancelled(true);
    }
    @EventHandler public void entityInteract(PlayerInteractEntityEvent event) {
        if(use(event.getPlayer(),event.getHand()))event.setCancelled(true);
    }
    @EventHandler public void entityInteractAt(PlayerInteractAtEntityEvent event) {
        if(use(event.getPlayer(),event.getHand()))event.setCancelled(true);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || holder.closed || player.getOpenInventory().getTopInventory()!=holder.inventory
                || event.getClick()!=ClickType.LEFT || holder.lastClick==Bukkit.getCurrentTick())return;
        int slot=event.getRawSlot();
        if(slot==22){holder.closed=true;player.closeInventory();Ui.sound(player,Ui.Cue.CLICK);return;}
        if(slot!=11 && slot!=13 && slot!=15)return;
        holder.lastClick=Bukkit.getCurrentTick();
        NotificationPreferences.values()[(slot-11)/2].toggle(player);
        render(player,holder);Ui.sound(player,Ui.Cue.CLICK);
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof Holder holder)holder.closed=true;
    }
    @EventHandler public void quit(PlayerQuitEvent event){openedAt.remove(event.getPlayer().getUniqueId());}
}
