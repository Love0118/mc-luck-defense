package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;

final class SummonAnnouncement {
    static boolean global(Rarity rarity) { return rarity==Rarity.MYTHIC || rarity==Rarity.PRIMORDIAL; }
    static void broadcast(Player owner, SummonRoll roll) {
        if(!global(roll.rarity()))return;
        Bukkit.broadcast(Component.text(owner.getName()+" 님이 ["+roll.rarity().label()+"] "+roll.type().label()+" 획득!",EntityAdapter.rarityColor(roll.rarity())));
        for(Player player:Bukkit.getOnlinePlayers()) player.playSound(player.getLocation(),
                roll.rarity()==Rarity.PRIMORDIAL?"minecraft:ui.toast.challenge_complete":"minecraft:block.amethyst_block.chime",
                SoundCategory.MASTER,.7f,roll.rarity()==Rarity.PRIMORDIAL?1f:1.15f);
    }
}
