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
    @Test void challengeRewardsRequireModeAndExactIndependentBudgets() {
        var plugin=mock(MomaPlugin.class);var player=mock(Player.class);var data=TraitSelectionsTest.data();
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(player.getAdvancementProgress(any())).thenReturn(mock(org.bukkit.advancement.AdvancementProgress.class));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getAdvancement(any())).thenReturn(mock(Advancement.class));
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            var service=new AchievementService(plugin);
            var arena=new Arena("a",UUID.randomUUID(),new Grid(6),2_000_000,100);
            arena.reachedRound(500);
            for(int i=0;i<750;i++)assertEquals(Arena.Result.OK,arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY),(t,r,c)->UUID.randomUUID()));
            assertEquals(1_500_000,arena.spentGold());
            service.challengesReached(player,arena,500);
            assertTrue(TraitSelections.unlocked(data,TraitCatalog.find("budget_500")));
            assertEquals(0,AchievementStats.get(data,Metric.SMALL_FORCE));
            var fresh=TraitSelectionsTest.data();when(player.getPersistentDataContainer()).thenReturn(fresh);
            arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.LEGENDARY),(t,r,c)->UUID.randomUUID());
            arena.select(arena.owner(),arena.lastSummoned().entityId());arena.sellSelected(arena.owner());
            service.challengesReached(player,arena,500);service.challengesReached(player,arena,600);
            assertFalse(TraitSelections.unlocked(fresh,TraitCatalog.find("budget_500")));
            assertTrue(TraitSelections.unlocked(fresh,TraitCatalog.find("budget_600")));
            var small=new Arena("b",UUID.randomUUID(),new Grid(6),30,100,TraitLoadout.EMPTY,new HashRandom(1),true);
            service.challengesReached(player,small,499);assertFalse(TraitSelections.unlocked(fresh,TraitCatalog.find("small_force_500")));
            service.challengesReached(player,small,500);assertTrue(TraitSelections.unlocked(fresh,TraitCatalog.find("small_force_500")));
            service.quickCleared(player,149);assertFalse(TraitSelections.unlocked(fresh,TraitCatalog.find("quick_clear_150")));
            service.quickCleared(player,150);service.quickCleared(player,1);
            assertTrue(TraitSelections.unlocked(fresh,TraitCatalog.find("quick_clear_150")));
        }
    }
    @Test void stableMilestonesHaveIncreasingThresholdsAndNativeChallengeFrames() {
        assertEquals(123,AchievementCatalog.ALL.size());
        assertEquals(123,AchievementCatalog.ALL.stream().map(Entry::id).distinct().count());
        for(Metric metric:Metric.values()) {
            var entries=AchievementCatalog.ALL.stream().filter(e->e.metric()==metric).toList();
            assertEquals(metric==Metric.ROUND?32:metric==Metric.MIRACLE || metric==Metric.QUICK_CLEAR?5:metric==Metric.ENHANCEMENT || metric==Metric.GOLD_SPENT?5:metric==Metric.DUPLICATE || metric==Metric.SMALL_FORCE?4:metric==Metric.SESSION?7:metric.role()?2:metric.budget()?1:10,entries.size());
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
            for(Rarity rarity:Rarity.values())SummonAnnouncement.broadcast(owner,new SummonRoll(UnitType.WOLF,rarity),SummonTier.NORMAL,p->p==owner);
            for(Player player:List.of(owner,viewer)) {
                Location at=player.getLocation();
                verify(player,times(4)).sendMessage(any(net.kyori.adventure.text.Component.class));
                verify(player).playSound(at,"minecraft:block.amethyst_block.chime",SoundCategory.MASTER,.7f,1.15f);
                verify(player).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.35f,1f);
                verify(player,times(2)).playSound(at,"minecraft:ui.toast.challenge_complete",SoundCategory.MASTER,.7f,.8f);
            }
        }
    }
}
