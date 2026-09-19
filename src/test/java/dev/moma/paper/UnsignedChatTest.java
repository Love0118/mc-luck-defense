package dev.moma.paper;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnsignedChatTest {
    @Test void chatIsCancelledAndRenderedForOnlyItsOriginalViewersWithoutSignature() {
        var plugin=mock(MomaPlugin.class);var handler=new UnsignedChat(plugin);var event=mock(AsyncChatEvent.class);
        Player sender=mock(Player.class);Audience allowed=mock(Audience.class),excluded=mock(Audience.class);
        Component message=Component.text("hello"),name=Component.text("user"),rendered=Component.text("[user] hello");
        var renderer=mock(ChatRenderer.class);when(event.getPlayer()).thenReturn(sender);when(event.message()).thenReturn(message);
        when(sender.displayName()).thenReturn(name);when(event.renderer()).thenReturn(renderer);when(event.viewers()).thenReturn(Set.of(allowed));
        when(renderer.render(sender,name,message,allowed)).thenReturn(rendered);
        handler.chat(event);verify(event).setCancelled(true);verify(allowed).sendMessage(rendered);verifyNoInteractions(excluded);
    }
    @Test void asynchronousDeliveryRunsOnMainThreadAndCancelledChatStaysCancelled() {
        var plugin=mock(MomaPlugin.class);var handler=new UnsignedChat(plugin);var event=mock(AsyncChatEvent.class);
        when(event.isCancelled()).thenReturn(true);handler.chat(event);verify(event,never()).viewers();
        when(event.isCancelled()).thenReturn(false);when(event.isAsynchronous()).thenReturn(true);
        when(event.viewers()).thenReturn(Set.of());
        var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);handler.chat(event);
            verify(scheduler).runTask(eq(plugin),any(Runnable.class));
        }
    }
    @Test void privateGlowBitPreservesEveryOtherEntityFlag() {
        for(int flags=0;flags<256;flags++) {
            int value=Byte.toUnsignedInt(PrivateGlow.glowing((byte)flags));
            assertEquals(flags|0x40,value);assertEquals(flags&~0x40,value&~0x40);
        }
    }
}
