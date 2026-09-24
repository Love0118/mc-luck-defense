package dev.moma.paper;

import dev.moma.core.*;
import java.util.List;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummonAnnouncementTest {
    @Test void exactDrawTierBoundariesKeepOnlyTheIntendedGrades() {
        for(int round:new int[]{1,100,101,499,500,999,1000,2500}) {
            SummonTier tier=SummonTier.atRound(round);
            for(Rarity rarity:Rarity.values()) {
                boolean expected=rarity==Rarity.TRUE_PRIMORDIAL || rarity==Rarity.MIRACLE
                        || rarity==Rarity.PRIMORDIAL && round<1000 || rarity==Rarity.MYTHIC && round<500;
                assertEquals(expected,SummonAnnouncement.global(rarity,tier),round+" "+rarity);
            }
        }
    }
    @Test void suppressedGradesSendNeitherServerTextNorGlobalSoundForEitherAnnouncementPath() {
        Player owner=mock(Player.class),viewer=mock(Player.class);World world=mock(World.class);
        when(owner.getName()).thenReturn("Tester");when(owner.getLocation()).thenReturn(new Location(world,0,65,0));
        when(viewer.getLocation()).thenReturn(new Location(world,100,65,0));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(owner,viewer));
            for(SummonTier tier:SummonTier.values())for(Rarity rarity:Rarity.values())for(boolean trait:new boolean[]{false,true}) {
                bukkit.clearInvocations();clearInvocations(owner,viewer);
                if(trait)SummonAnnouncement.traitBroadcast(owner,new SummonRoll(UnitType.WOLF,rarity),tier);
                else SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,rarity),tier);
                int expected=SummonAnnouncement.global(rarity,tier)?1:0;
                bukkit.verify(()->Bukkit.broadcast(any(net.kyori.adventure.text.Component.class)),times(expected));
                for(Player p:List.of(owner,viewer))verify(p,times(expected)).playSound(any(Location.class),anyString(),eq(SoundCategory.MASTER),anyFloat(),anyFloat());
            }
        }
    }
}
