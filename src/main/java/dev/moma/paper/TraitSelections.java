package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.*;

/** Counters are permanent unlock evidence; only the chosen loadout needs new storage. */
final class TraitSelections {
    private static final NamespacedKey KEY=new NamespacedKey("mcluckdefense","trait_loadout");
    static long highest(PersistentDataContainer data) {
        return data==null?0:AchievementStats.get(data,AchievementCatalog.Metric.ROUND);
    }
    static boolean unlocked(PersistentDataContainer data,TraitCatalog.Entry entry) {
        return data!=null && AchievementStats.get(data,entry.achievement().metric())>=entry.achievement().target();
    }
    static TraitLoadout load(PersistentDataContainer data) {
        if(data==null)return TraitLoadout.EMPTY;
        String saved=data.get(KEY,PersistentDataType.STRING);
        if(saved==null || saved.isBlank())return TraitLoadout.EMPTY;
        List<String> ids=new ArrayList<>();Set<TraitCatalog.Family> families=EnumSet.noneOf(TraitCatalog.Family.class);
        int slots=TraitCatalog.slots(highest(data));
        for(String id:saved.split(",")) {
            var entry=TraitCatalog.find(id);
            if(ids.size()<slots && entry!=null && unlocked(data,entry) && families.add(entry.family()))ids.add(id);
        }
        return new TraitLoadout(ids);
    }
    static void save(PersistentDataContainer data,List<String> ids) {
        TraitLoadout loadout=TraitLoadout.unlocked(ids,highest(data),e->unlocked(data,e));
        data.set(KEY,PersistentDataType.STRING,String.join(",",loadout.ids()));
    }
    private TraitSelections() {}
}
