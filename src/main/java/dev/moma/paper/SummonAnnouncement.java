package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

final class SummonAnnouncement {
    static boolean global(Rarity rarity,SummonTier tier) {
        Rarity minimum=switch(tier) {
            case NORMAL, ADVANCED -> Rarity.MYTHIC;
            case ASCENDED -> Rarity.PRIMORDIAL;
            case MIRACLE -> Rarity.TRUE_PRIMORDIAL;
        };
        return rarity.ordinal()>=minimum.ordinal();
    }
    static void broadcast(Player owner,SummonRoll roll,SummonTier tier) {
        announce(owner,roll,tier,false);
    }
    static void traitBroadcast(Player owner,SummonRoll roll,SummonTier tier) {
        announce(owner,roll,tier,true);
    }
    private static void announce(Player owner,SummonRoll roll,SummonTier tier,boolean traitUpgrade) {
        if(!global(roll.rarity(),tier))return;
        boolean ascended=roll.rarity().ordinal()>=Rarity.TRUE_PRIMORDIAL.ordinal();
        Bukkit.broadcast(EntityAdapter.rarityName(roll.rarity(),owner.getName()+" 님이 ["+roll.rarity().label()+"] "+roll.type().label()+(traitUpgrade?" 특성 승급!":" 획득!")));
        for(Player player:Bukkit.getOnlinePlayers()) player.playSound(player.getLocation(),
                roll.rarity()==Rarity.MYTHIC?"minecraft:block.amethyst_block.chime":"minecraft:ui.toast.challenge_complete",
                SoundCategory.MASTER,.7f,ascended?.8f:roll.rarity()==Rarity.PRIMORDIAL?1f:1.15f);
    }
}
