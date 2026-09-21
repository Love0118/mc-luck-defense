package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;

final class SummonAnnouncement {
    static boolean global(Rarity rarity) { return rarity==Rarity.MYTHIC || rarity==Rarity.PRIMORDIAL || rarity==Rarity.TRUE_PRIMORDIAL; }
    static void broadcast(Player owner, SummonRoll roll) {
        announce(owner,roll,false);
    }
    static void traitBroadcast(Player owner,SummonRoll roll) {
        announce(owner,roll,true);
    }
    private static void announce(Player owner,SummonRoll roll,boolean traitUpgrade) {
        if(!global(roll.rarity()))return;
        boolean ascended=roll.rarity()==Rarity.TRUE_PRIMORDIAL;
        Bukkit.broadcast(Component.text(owner.getName()+" 님이 ["+roll.rarity().label()+"] "+roll.type().label()+(traitUpgrade?" 특성 승급!":ascended?" 승급!":" 획득!"),EntityAdapter.rarityColor(roll.rarity()))
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD,ascended));
        for(Player player:Bukkit.getOnlinePlayers()) player.playSound(player.getLocation(),
                roll.rarity()==Rarity.MYTHIC?"minecraft:block.amethyst_block.chime":"minecraft:ui.toast.challenge_complete",
                SoundCategory.MASTER,.7f,ascended?.8f:roll.rarity()==Rarity.PRIMORDIAL?1f:1.15f);
    }
}
