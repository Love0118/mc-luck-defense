package dev.moma.paper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Compass picker opens the selected game's entry screen. */
final class MinigameMenu implements Listener {
    static final int MOMA=11,MINE=15,CLOSE=22;
    private final MomaPlugin plugin;
    private final GameService games;
    private final Lobby lobby;
    private final LobbyMenu sessions;
    private final dev.moma.mining.MiningGame mining;
    private static final class Holder implements InventoryHolder {
        final Player owner;
        Inventory inventory;
        boolean queued,closed;
        Holder(Player owner){this.owner=owner;}
        @Override public Inventory getInventory(){return inventory;}
    }
    MinigameMenu(MomaPlugin plugin,GameService games,Lobby lobby,LobbyMenu sessions,dev.moma.mining.MiningGame mining){
        this.plugin=plugin;this.games=games;this.lobby=lobby;this.sessions=sessions;this.mining=mining;
    }
    private boolean eligible(Player player) {
        return player.isOnline() && player.hasPermission("moma.play") && !games.playing(player)
                && (games.quizCabinOwner(player)||games.watching(player)||mining!=null&&mining.owns(player)||lobby!=null&&lobby.contains(player.getLocation()));
    }
    void open(Player player) {
        if(!eligible(player))return;
        Holder holder=new Holder(player);holder.inventory=Bukkit.createInventory(holder,27,Ui.text("&b미니게임"));
        holder.inventory.setItem(MOMA,Ui.item(Material.GRASS_BLOCK,"&a마운디","&7게임 참가 / 관전"));
        if(mining!=null)holder.inventory.setItem(MINE,Ui.item(Material.IRON_PICKAXE,"&e광산","&7입장"));
        holder.inventory.setItem(CLOSE,Ui.item(Material.BARRIER,"&c닫기"));
        player.openInventory(holder.inventory);Ui.sound(player,Ui.Cue.OPEN);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||player!=holder.owner||holder.closed||holder.queued
                ||!eligible(player)||player.getOpenInventory().getTopInventory()!=holder.inventory||event.getClick()!=ClickType.LEFT)return;
        int slot=event.getRawSlot();if(slot!=MOMA&&slot!=CLOSE&&(slot!=MINE||mining==null))return;
        holder.queued=true;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(holder.closed||!eligible(player)||player.getOpenInventory().getTopInventory()!=holder.inventory)return;
            player.closeInventory();
            try {
                if(slot==MOMA)sessions.open(player);
                else if(slot==MINE)mining.join(player);
                Ui.sound(player,Ui.Cue.CLICK);
            } catch(IllegalArgumentException|IllegalStateException error) {
                player.sendMessage(Ui.text("&c"+error.getMessage()));Ui.sound(player,Ui.Cue.ERROR);
            }
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof Holder holder)holder.closed=true;
    }
}
