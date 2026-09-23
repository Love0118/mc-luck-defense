package dev.moma.paper;

import dev.moma.core.*;
import dev.moma.core.AchievementCatalog.*;
import com.google.gson.JsonParser;
import java.util.*;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AchievementTest {
    @Test void stableMilestonesHaveIncreasingThresholdsAndNativeChallengeFrames() {
        assertEquals(104,AchievementCatalog.ALL.size());
        assertEquals(104,AchievementCatalog.ALL.stream().map(Entry::id).distinct().count());
        for(Metric metric:Metric.values()) {
            var entries=AchievementCatalog.ALL.stream().filter(e->e.metric()==metric).toList();
            assertEquals(metric==Metric.ROUND?32:metric==Metric.MIRACLE?5:metric==Metric.ENHANCEMENT || metric==Metric.GOLD_SPENT?5:metric==Metric.DUPLICATE?4:metric==Metric.SESSION?7:metric.role()?1:10,entries.size());
            long previous=0;
            for(Entry entry:entries) {
                assertTrue(entry.target()>previous);previous=entry.target();
                var json=JsonParser.parseString(AchievementService.definition(entry,null)).getAsJsonObject();
                assertEquals(entry.challenge()?"challenge":"task",json.getAsJsonObject("display").get("frame").getAsString());
                assertEquals("minecraft:impossible",json.getAsJsonObject("criteria").getAsJsonObject("earned").get("trigger").getAsString());
            }
        }
        assertTrue(AchievementCatalog.ALL.stream().filter(e->e.metric()==Metric.PRIMORDIAL).allMatch(Entry::challenge));
        assertTrue(AchievementCatalog.ALL.stream().filter(e->e.metric()==Metric.TRUE_PRIMORDIAL).allMatch(e->e.challenge() && e.description().contains("획득")));
    }
    @Test void persistentCountersAreIndependentAndRoundNeverDecreases() {
        var values=new HashMap<NamespacedKey,Long>();var data=mock(PersistentDataContainer.class);
        when(data.getOrDefault(any(),eq(PersistentDataType.LONG),anyLong())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),eq(PersistentDataType.LONG),anyLong());
        assertEquals(1,AchievementStats.summoned(data,Metric.MYTHIC));
        assertEquals(2,AchievementStats.summoned(data,Metric.MYTHIC));
        assertEquals(0,AchievementStats.get(data,Metric.PRIMORDIAL));
        assertEquals(1,AchievementStats.summoned(data,Metric.TRUE_PRIMORDIAL));
        assertEquals(0,AchievementStats.get(data,Metric.PRIMORDIAL));
        assertEquals(1,AchievementStats.summoned(data,Metric.EPIC));
        assertEquals(1,AchievementStats.summoned(data,Metric.SESSION));
        assertEquals(2,AchievementStats.summoned(data,Metric.SESSION));
        assertEquals(1,AchievementStats.get(data,Metric.EPIC));
        assertEquals(1000,AchievementStats.reached(data,1000));assertEquals(1000,AchievementStats.reached(data,1));
        values.put(new NamespacedKey("mcluckdefense","achievement_mythic"),Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE,AchievementStats.summoned(data,Metric.MYTHIC));
        assertThrows(IllegalArgumentException.class,()->AchievementStats.summoned(data,Metric.ROUND));
    }
    @Test void vanillaRecipesAndCustomAdvancementsAreNotBlocked() {
        Advancement advancement=mock(Advancement.class);when(advancement.getKey()).thenReturn(NamespacedKey.minecraft("recipes/test"));
        assertFalse(AchievementService.vanillaDisplay(advancement));
        when(advancement.getDisplay()).thenReturn(mock(io.papermc.paper.advancement.AdvancementDisplay.class));
        assertTrue(AchievementService.vanillaDisplay(advancement));
        when(advancement.getKey()).thenReturn(new NamespacedKey("mcluckdefense","round_1"));
        assertFalse(AchievementService.vanillaDisplay(advancement));
    }
    @Test void mythicAndHigherBroadcastWithSoundForEarlyDrawTiers() {
        Player owner=mock(Player.class),viewer=mock(Player.class);World world=mock(World.class);
        when(owner.getName()).thenReturn("Tester");when(owner.getLocation()).thenReturn(new Location(world,0,70,0));when(viewer.getLocation()).thenReturn(new Location(world,100,70,0));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(owner,viewer));
            for(Rarity rarity:Rarity.values())SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,rarity),SummonTier.NORMAL);
            bukkit.verify(()->Bukkit.broadcast(any(net.kyori.adventure.text.Component.class)),times(4));
            for(Player player:List.of(owner,viewer)) {
                Location at=player.getLocation();
                verify(player).playSound(at,"minecraft:block.amethyst_block.chime",SoundCategory.MASTER,.7f,1.15f);
                verify(player).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.7f,1f);
                verify(player,times(2)).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.7f,.8f);
            }
        }
    }
}
