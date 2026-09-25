package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.function.Predicate;

final class SummonAnnouncement {
    static boolean global(Rarity rarity,SummonTier tier) {
        return rarity.ordinal()>=(tier==SummonTier.NORMAL?Rarity.MYTHIC:Rarity.PRIMORDIAL).ordinal();
    }
    static void broadcast(Player owner,SummonRoll roll,SummonTier tier,Predicate<Player> sameSession) {
        broadcast(owner,roll,tier,sameSession,true);
    }
    static void broadcast(Player owner,SummonRoll roll,SummonTier tier,Predicate<Player> sameSession,boolean playSound) {
        announce(owner,roll,tier,sameSession,false,playSound);
    }
    static void traitBroadcast(Player owner,SummonRoll roll,SummonTier tier,Predicate<Player> sameSession) {
        traitBroadcast(owner,roll,tier,sameSession,true);
    }
    static void traitBroadcast(Player owner,SummonRoll roll,SummonTier tier,Predicate<Player> sameSession,boolean playSound) {
        announce(owner,roll,tier,sameSession,true,playSound);
    }
    private static void announce(Player owner,SummonRoll roll,SummonTier tier,Predicate<Player> sameSession,boolean traitUpgrade,boolean playSound) {
        if(!global(roll.rarity(),tier))return;
        boolean ascended=roll.rarity().ordinal()>=Rarity.TRUE_PRIMORDIAL.ordinal();
        var message=EntityAdapter.rarityName(roll.rarity(),owner.getName()+" 님이 ["+roll.rarity().label()+"] "+roll.type().label()+(traitUpgrade?" 특성 승급!":" 획득!"));
        for(Player player:Bukkit.getOnlinePlayers()) {
            boolean local=sameSession.test(player);
            if(!local && !SessionTools.otherSummonAlerts(player))continue;
            player.sendMessage(message);
            if(!playSound)continue;
            if(roll.rarity()==Rarity.PRIMORDIAL && tier!=SummonTier.NORMAL && !local)continue;
            player.playSound(player.getLocation(),roll.rarity()==Rarity.MYTHIC?"minecraft:block.amethyst_block.chime":"minecraft:ui.toast.challenge_complete",
                    SoundCategory.MASTER,roll.rarity()==Rarity.PRIMORDIAL?.35f:.7f,
                    ascended?.8f:roll.rarity()==Rarity.PRIMORDIAL?1f:1.15f);
        }
    }
}
