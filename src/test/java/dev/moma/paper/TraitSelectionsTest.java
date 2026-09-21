package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraitSelectionsTest {
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
        assertEquals(List.of("round_100","round_125"),TraitSelections.load(data).ids());
        assertThrows(IllegalArgumentException.class,()->TraitSelections.save(data,List.of("round_100","round_125","round_200")));
        assertThrows(IllegalArgumentException.class,()->TraitSelections.save(data,List.of("mythic_10")));
        data.set(new NamespacedKey("mcluckdefense","trait_loadout"),PersistentDataType.STRING,"missing,round_100,round_150,mythic_10,round_125");
        assertEquals(List.of("round_100","round_125"),TraitSelections.load(data).ids());
        AchievementStats.reached(data,500);TraitSelections.save(data,List.of("round_100","round_125","round_200"));
        assertEquals(3,TraitSelections.load(data).entries().size());
    }
}
