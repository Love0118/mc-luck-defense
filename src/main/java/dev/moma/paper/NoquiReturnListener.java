package dev.moma.paper;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

final class NoquiReturnListener implements Listener {
    private final GameService games;
    private final Map<UUID,Integer> clicks=new HashMap<>();
    NoquiReturnListener(GameService games){this.games=games;}
    private boolean use(Player player,EquipmentSlot hand) {
        if(hand!=EquipmentSlot.HAND || !games.tools.holding(player,"noqui_return"))return false;
        if(!games.canReturnToNoqui(player)){games.refreshNoquiTool(player);return true;}
        int tick=Bukkit.getCurrentTick();
        if(clicks.getOrDefault(player.getUniqueId(),-1)==tick)return true;
        clicks.put(player.getUniqueId(),tick);games.requestNoquiReturn(player);return true;
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if((event.getAction()==Action.RIGHT_CLICK_AIR || event.getAction()==Action.RIGHT_CLICK_BLOCK) && use(event.getPlayer(),event.getHand()))event.setCancelled(true);
    }
    @EventHandler public void entity(PlayerInteractEntityEvent event){if(use(event.getPlayer(),event.getHand()))event.setCancelled(true);}
    @EventHandler public void entityAt(PlayerInteractAtEntityEvent event){if(use(event.getPlayer(),event.getHand()))event.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent event){clicks.remove(event.getPlayer().getUniqueId());}
}
