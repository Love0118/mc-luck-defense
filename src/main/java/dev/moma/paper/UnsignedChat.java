package dev.moma.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.event.*;

/** Re-delivers normal chat as unsigned system messages; never forwards a SignedMessage. */
final class UnsignedChat implements Listener {
    private final MomaPlugin plugin;
    UnsignedChat(MomaPlugin plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        event.setCancelled(true);
        var viewers = List.copyOf(event.viewers());
        var renderer = event.renderer(); var message = event.message(); var player = event.getPlayer();
        Runnable delivery = () -> {
            var displayName = player.displayName();
            for (var viewer : viewers) viewer.sendMessage(renderer.render(player, displayName, message, viewer));
        };
        if (event.isAsynchronous()) Bukkit.getScheduler().runTask(plugin, delivery); else delivery.run();
    }
}
