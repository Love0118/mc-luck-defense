package dev.moma.paper;

import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class BgmChatInputTest {
    @Test void pendingLegacyInputIsCapturedOnceAndHiddenBeforeAndAfterConverter() {
        Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        for(String original:List.of("https://www.youtube.com/watch?v=AbCdEf123_-&t=2","OAuth_AbCd-123","취소")) {
            List<String> inputs=new ArrayList<>();Map<UUID,Consumer<String>> pending=new HashMap<>();pending.put(id,inputs::add);
            BgmChatInput input=new BgmChatInput(pending::remove);
            var event=new AsyncPlayerChatEvent(true,player,original,new HashSet<>(Set.of(player)));
            input.legacy(event);assertEquals(List.of(original),inputs);assertTrue(event.isCancelled());assertEquals("",event.getMessage());assertTrue(event.getRecipients().isEmpty());
            // Even a converter which ignores cancellation cannot see or leak the private value.
            event.setMessage("converter output");event.setCancelled(false);event.getRecipients().add(player);
            input.legacyGuard(event);assertTrue(event.isCancelled());assertEquals("",event.getMessage());assertTrue(event.getRecipients().isEmpty());
            input.legacy(event);assertEquals(1,inputs.size());
            var normal=new AsyncPlayerChatEvent(true,player,"normal chat",new HashSet<>(Set.of(player)));
            input.legacy(normal);input.legacyGuard(normal);assertFalse(normal.isCancelled());assertEquals("normal chat",normal.getMessage());
        }
    }
    @Test void paperOnlyPathUsesOriginalUnmodifiedComponent() {
        Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        List<String> captured=new ArrayList<>();Map<UUID,Consumer<String>> pending=new HashMap<>();pending.put(id,captured::add);
        BgmChatInput input=new BgmChatInput(pending::remove);var event=mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(player);when(event.originalMessage()).thenReturn(Component.text("https://youtu.be/AbCdEf123_-"));
        when(event.message()).thenReturn(Component.text("변환된 메시지"));var viewers=new HashSet<net.kyori.adventure.audience.Audience>(Set.of(player));when(event.viewers()).thenReturn(viewers);
        input.modern(event);input.modernGuard(event);
        assertEquals(List.of("https://youtu.be/AbCdEf123_-"),captured);assertTrue(viewers.isEmpty());verify(event,times(2)).setCancelled(true);
        verify(event,never()).message();
    }
}
