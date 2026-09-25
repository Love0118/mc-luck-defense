package dev.moma.paper;

import dev.moma.core.*;
import java.util.List;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummonAnnouncementTest {
    @Test void mythicBroadcastEndsAfterRoundOneHundredButPrimordialTextContinues() {
        for(int round:new int[]{1,100,101,499,500,999,1000,2500}) {
            SummonTier tier=SummonTier.atRound(round);
            for(Rarity rarity:Rarity.values()) {
                boolean expected=rarity==Rarity.TRUE_PRIMORDIAL || rarity==Rarity.MIRACLE
                        || rarity==Rarity.PRIMORDIAL || rarity==Rarity.MYTHIC && round<=100;
                assertEquals(expected,SummonAnnouncement.global(rarity,tier),round+" "+rarity);
            }
        }
    }
    @Test void otherSessionSettingFiltersTextAndSoundAndLatePrimordialSoundStaysLocal() {
        Player owner=mock(Player.class),watcher=mock(Player.class),remote=mock(Player.class),muted=mock(Player.class);
        World world=mock(World.class);Location at=new Location(world,0,65,0);
        when(owner.getName()).thenReturn("Tester");
        for(Player player:List.of(owner,watcher,remote,muted))when(player.getLocation()).thenReturn(at);
        var data=mock(PersistentDataContainer.class);
        when(muted.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(),eq(PersistentDataType.BYTE),eq((byte)1))).thenReturn((byte)0);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(owner,watcher,remote,muted));
            var local=(java.util.function.Predicate<Player>)player->player==owner || player==watcher;
            SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL),SummonTier.ADVANCED,local);
            for(Player player:List.of(owner,watcher,remote))verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(muted,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
            for(Player player:List.of(owner,watcher))verify(player).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.35f,1f);
            verify(remote,never()).playSound(any(Location.class),anyString(),any(),anyFloat(),anyFloat());
            verify(muted,never()).playSound(any(Location.class),anyString(),any(),anyFloat(),anyFloat());
            clearInvocations(owner,watcher,remote,muted);
            SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL),SummonTier.NORMAL,local);
            verify(remote).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.35f,1f);
            clearInvocations(owner,watcher,remote,muted);
            SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,Rarity.MYTHIC),SummonTier.ADVANCED,local);
            for(Player player:List.of(owner,watcher,remote,muted))verify(player,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }
}
