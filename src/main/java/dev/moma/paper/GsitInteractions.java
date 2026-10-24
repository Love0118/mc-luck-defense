package dev.moma.paper;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Harmless clicks that GSit may consume after the normal protection listeners. */
final class GsitInteractions {
    private GsitInteractions() {}

    static boolean seatClick(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK
                ||event.getBlockFace()!=BlockFace.UP||event.getItem()!=null||event.getClickedBlock()==null
                ||!event.getPlayer().isValid()||event.getPlayer().isSneaking())return false;
        String material=event.getClickedBlock().getType().name();
        boolean seat=material.endsWith("_STAIRS")||material.endsWith("_SLAB")||material.endsWith("_CARPET")
                ||material.equals("SNOW")||material.equals("SHELF_MUSHROOM");
        return seat&&enabled();
    }

    static boolean playerClick(PlayerInteractEntityEvent event) {
        Player player=event.getPlayer();
        if(event.getHand()!=EquipmentSlot.HAND||!player.isValid()||player.isSneaking()
                ||player.getGameMode()==GameMode.SPECTATOR
                ||!(event.getRightClicked() instanceof Player target)||target==player
                ||!target.isValid()||!target.isOnline()
                ||player.getInventory().getItemInMainHand().getType()!=Material.AIR)return false;
        return enabled();
    }

    private static boolean enabled() {
        // GSit checks permissions, world settings, toggles and seat safety itself.
        // Callers only skip their own cancellation; an existing cancellation stays intact.
        return Bukkit.getPluginManager().isPluginEnabled("GSit");
    }
}
