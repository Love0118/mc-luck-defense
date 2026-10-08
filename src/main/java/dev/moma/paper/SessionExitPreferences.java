package dev.moma.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

final class SessionExitPreferences {
    private static final NamespacedKey LOCK_AFTER_100 = new NamespacedKey("momadefense", "lock_session_exit_after_100");
    private SessionExitPreferences() {}
    static boolean enabled(Player player) {
        var data=player.getPersistentDataContainer();
        return data!=null && data.getOrDefault(LOCK_AFTER_100,PersistentDataType.BYTE,(byte)0)!=0;
    }
    static void toggle(Player player) {
        player.getPersistentDataContainer().set(LOCK_AFTER_100,PersistentDataType.BYTE,enabled(player)?(byte)0:(byte)1);
    }
}
