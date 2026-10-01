package dev.moma.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

enum NotificationPreferences {
    OWN_SUMMON("own_summon_alerts", "내 소환 알림"),
    OTHER_SUMMON("other_summon_alerts", "다른 소환 알림"),
    ROUND("round_alerts", "라운드 알림");

    private final NamespacedKey key;
    final String label;
    NotificationPreferences(String key,String label) {
        this.key=new NamespacedKey("momadefense",key);this.label=label;
    }
    boolean enabled(Player player) {
        var data=player.getPersistentDataContainer();
        return data==null || data.getOrDefault(key,PersistentDataType.BYTE,(byte)1)!=0;
    }
    void toggle(Player player) {
        player.getPersistentDataContainer().set(key,PersistentDataType.BYTE,enabled(player)?(byte)0:(byte)1);
    }
    static boolean summonEnabled(Player recipient,Player owner) {
        return (recipient.getUniqueId().equals(owner.getUniqueId())?OWN_SUMMON:OTHER_SUMMON).enabled(recipient);
    }
}
