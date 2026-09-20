package dev.moma.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/** Capture private input before legacy converters such as KAKC; never change their global/player settings. */
@SuppressWarnings("deprecation")
final class BgmChatInput implements Listener {
    private final Function<UUID,Consumer<String>> claim;
    private final Set<AsyncPlayerChatEvent> legacy=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<AsyncChatEvent> modern=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    BgmChatInput(Function<UUID,Consumer<String>> claim){this.claim=claim;}
    @EventHandler(priority=EventPriority.LOWEST)
    public void legacy(AsyncPlayerChatEvent event) {
        Consumer<String> input=claim.apply(event.getPlayer().getUniqueId());if(input==null)return;
        String original=event.getMessage();legacy.add(event);hide(event);input.accept(original.trim());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void legacyGuard(AsyncPlayerChatEvent event) {if(legacy.remove(event))hide(event);}
    private void hide(AsyncPlayerChatEvent event){event.setCancelled(true);event.setMessage("");event.getRecipients().clear();}
    @EventHandler(priority=EventPriority.LOWEST)
    public void modern(AsyncChatEvent event) {
        Consumer<String> input=claim.apply(event.getPlayer().getUniqueId());if(input==null)return;
        String original=PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        modern.add(event);hide(event);input.accept(original.trim());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void modernGuard(AsyncChatEvent event){if(modern.remove(event))hide(event);}
    private void hide(AsyncChatEvent event){event.setCancelled(true);event.message(Component.empty());event.viewers().clear();}
}
