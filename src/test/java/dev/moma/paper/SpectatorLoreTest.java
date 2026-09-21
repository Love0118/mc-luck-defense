package dev.moma.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpectatorLoreTest {
    @Test void refreshShowsCurrentRoundAndSongWithoutReorderingSessionTargets() {
        var plugin=mock(MomaPlugin.class);var games=mock(GameService.class);games.bgm=mock(BgmService.class);
        var player=mock(Player.class);UUID owner=UUID.randomUUID(),sessionId=UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);when(player.hasPermission("moma.play")).thenReturn(true);
        var inventory=mock(Inventory.class);var view=mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);when(view.getTopInventory()).thenReturn(inventory);
        when(games.activeSessions()).thenReturn(List.of(new GameService.SessionInfo(sessionId,owner,"Owner","arena",0,0)));
        when(games.bgm.nowPlaying(sessionId)).thenReturn("재생 대기 중");
        var loreByItem=new IdentityHashMap<ItemStack,List<Component>>();
        try(var bukkit=mockStatic(Bukkit.class);
            var items=mockConstruction(ItemStack.class,(item,context)->{
                var meta=mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);
                doAnswer(call->{loreByItem.put(item,call.getArgument(0));return null;}).when(meta).lore(anyList());
            })) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class)))
                    .thenAnswer(call->{when(inventory.getHolder()).thenReturn(call.getArgument(0));return inventory;});
            var menu=new LobbyMenu(plugin,games);menu.open(player);
            assertTrue(loreByItem.values().stream().anyMatch(lore->plain(lore).contains("시작 준비 중")));
            assertTrue(loreByItem.values().stream().anyMatch(lore->plain(lore).contains("관전자 0명")));
            clearInvocations(inventory,player);
            var other=new GameService.SessionInfo(UUID.randomUUID(),UUID.randomUUID(),"Other","other",90,2);
            when(games.activeSessions()).thenReturn(List.of(other,new GameService.SessionInfo(sessionId,owner,"Owner","arena",101,7)));
            when(games.bgm.nowPlaying(sessionId)).thenReturn("Song &a literal");
            when(games.spectatorCount(sessionId)).thenReturn(3L);
            menu.refreshOpen();
            var item=org.mockito.ArgumentCaptor.forClass(ItemStack.class);verify(inventory).setItem(eq(0),item.capture());
            String text=plain(loreByItem.get(item.getValue()));
            assertTrue(text.contains("진행 라운드 101"));assertTrue(text.contains("남은 적 7"));
            assertTrue(text.contains("재생 중인 곡 Song &a literal"));
            assertTrue(text.contains("관전자 3명"));
            assertFalse(text.contains("전장"));
            assertFalse(text.contains("arena"));
            verify(player,never()).openInventory(any(Inventory.class));
            clearInvocations(inventory);when(games.activeSessions()).thenReturn(List.of(other));menu.refreshOpen();
            verify(inventory).setItem(eq(0),any(ItemStack.class));
            verify(games.bgm,times(2)).nowPlaying(sessionId);
        }
    }
    private static String plain(List<Component> lore) {
        return String.join("\n",lore.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }
}
