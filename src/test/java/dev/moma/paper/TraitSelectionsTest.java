package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraitSelectionsTest {
    @Test void unlockedPassivesApplyWithoutSavedSelectionOrSlotsAndKeepHighestPromotion() {
        var data=data();AchievementStats.add(data,AchievementCatalog.Metric.SESSION,1000);
        var fresh=TraitSelections.load(data);
        assertTrue(fresh.ids().isEmpty());assertEquals(4,fresh.passives().size());
        AchievementStats.reached(data,350);
        var promoted=TraitSelections.load(data);
        assertEquals(5,promoted.passives().size());
        assertEquals(Rarity.MYTHIC,promoted.summonedRarity(Rarity.EPIC,0));
        assertEquals(Rarity.MYTHIC,promoted.summonedRarity(Rarity.MYTHIC,0));
        assertEquals(Rarity.COMMON,promoted.summonedRarity(Rarity.COMMON,2));
    }
    static PersistentDataContainer data() {
        var data=mock(PersistentDataContainer.class);var values=new HashMap<NamespacedKey,Object>();
        when(data.get(any(),any())).thenAnswer(c->values.get(c.getArgument(0)));
        when(data.getOrDefault(any(),any(),any())).thenAnswer(c->values.getOrDefault(c.getArgument(0),c.getArgument(2)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(),any(),any());
        return data;
    }
    @Test void oldCountersUnlockRewardsAndSavedLoadoutIsRevalidated() {
        var data=data();AchievementStats.reached(data,250);
        assertTrue(TraitSelections.unlocked(data,TraitCatalog.find("round_100")));
        TraitSelections.save(data,List.of("round_100","round_125"));
        assertEquals(List.of("round_100"),TraitSelections.load(data).ids());
        assertThrows(IllegalArgumentException.class,()->TraitSelections.save(data,List.of("round_100","round_200","enhancement_100")));
        assertThrows(IllegalArgumentException.class,()->TraitSelections.save(data,List.of("mythic_10")));
        data.set(new NamespacedKey("mcluckdefense","trait_loadout"),PersistentDataType.STRING,"missing,round_100,round_150,mythic_10,round_125");
        assertEquals(List.of("round_100"),TraitSelections.load(data).ids());
        AchievementStats.reached(data,500);TraitSelections.save(data,List.of("round_100","round_125","round_200"));
        assertEquals(2,TraitSelections.load(data).entries().size());
        assertEquals(List.of("round_350"),TraitSelections.load(data).passives().stream().map(TraitCatalog.Entry::id).toList());
    }
}
