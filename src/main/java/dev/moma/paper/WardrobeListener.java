package dev.moma.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

final class WardrobeListener implements Listener {
    private final GameService games;
    WardrobeListener(GameService games){this.games=games;}
    @EventHandler public void interact(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||!event.getAction().isRightClick()||!games.tools.holding(event.getPlayer(),"wardrobe"))return;
        event.setCancelled(true);games.noqui.openWardrobe(event.getPlayer());
    }
    @EventHandler public void entity(PlayerInteractEntityEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||!games.tools.holding(event.getPlayer(),"wardrobe"))return;
        event.setCancelled(true);games.noqui.openWardrobe(event.getPlayer());
    }
}
