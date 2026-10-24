package dev.moma.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Shared with ServerChatMirror's /chatmirror on/off persisted receive preference. */
final class ChatMirrorPreferences {
    private static final NamespacedKey HIDDEN = new NamespacedKey("serverchatmirror", "hidden");
    private ChatMirrorPreferences() {}
    static boolean enabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(HIDDEN, PersistentDataType.BYTE, (byte) 0) != 1;
    }
    static void toggle(Player player) {
        var data = player.getPersistentDataContainer();
        if (enabled(player)) data.set(HIDDEN, PersistentDataType.BYTE, (byte) 1);
        else data.remove(HIDDEN);
    }
}
