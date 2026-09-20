package dev.moma.paper;

import dev.moma.core.AchievementCatalog.Metric;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.*;

/** Stored in playerdata so reconnects and server restarts retain the counters. */
final class AchievementStats {
    private static NamespacedKey key(Metric metric) {
        return new NamespacedKey("mcluckdefense", "achievement_"+metric.name().toLowerCase(Locale.ROOT));
    }
    static long get(PersistentDataContainer data, Metric metric) {
        return Math.max(0, data.getOrDefault(key(metric), PersistentDataType.LONG, 0L));
    }
    static long reached(PersistentDataContainer data, int round) {
        if(round<0)throw new IllegalArgumentException("Negative round");
        long value=Math.max(get(data,Metric.ROUND),round);
        data.set(key(Metric.ROUND),PersistentDataType.LONG,value);return value;
    }
    static long summoned(PersistentDataContainer data, Metric metric) {
        if(metric==Metric.ROUND)throw new IllegalArgumentException("Round is a maximum, not a summon counter");
        long old=get(data,metric),value=old==Long.MAX_VALUE?old:old+1;
        data.set(key(metric),PersistentDataType.LONG,value);return value;
    }
}
