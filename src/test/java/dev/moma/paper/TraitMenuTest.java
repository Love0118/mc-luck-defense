package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraitMenuTest {
    @Test void cabinOwnerEditsExistingTraitDataWhilePermissionAndActiveGuardsStillApply() {
        try(var ui=new MenuFixture(true)) {
            AchievementStats.reached(ui.data,100);
            ui.menu.open(ui.player);assertNotNull(ui.current);
            ui.click("출전 수당");assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
            ui.click("출전 수당 · 10골드");assertEquals(List.of("round_100"),TraitSelections.load(ui.data).ids());
            clearInvocations(ui.player);when(ui.games.active(ui.player)).thenReturn(true);ui.menu.open(ui.player);ui.click(0);
            verify(ui.player,never()).openInventory(any(Inventory.class));assertEquals(List.of("round_100"),TraitSelections.load(ui.data).ids());
            when(ui.games.active(ui.player)).thenReturn(false);when(ui.player.hasPermission("moma.play")).thenReturn(false);ui.menu.open(ui.player);
            when(ui.player.hasPermission("moma.play")).thenReturn(true);when(ui.games.quizCabinOwner(ui.player)).thenReturn(false);ui.menu.open(ui.player);
            verify(ui.player,never()).openInventory(any(Inventory.class));
        }
    }
    @Test void booksShowEffectsAndEveryTraitAppearsInExactlyOneGroup() {
        try(var ui=new MenuFixture(false)) {
            ui.menu.open(ui.player);
            var overview=ui.current;var books=new LinkedHashMap<Integer,String>();
            for(int slot=0;slot<36;slot++) {
                var book=overview.getItem(slot);if(book==null)continue;
                assertEquals(Material.ENCHANTED_BOOK,book.getType());books.put(slot,ui.name(book));
            }
            assertEquals(17,books.size());assertEquals(books.size(),new HashSet<>(books.values()).size());
            var seen=new HashSet<String>();
            for(var book:books.entrySet()) {
                ui.click(book.getKey());assertNotSame(overview,ui.current);
                var effects=ui.lore(overview.getItem(book.getKey()));
                for(int slot=0;slot<36;slot++) {
                    var item=ui.current.getItem(slot);if(item==null)continue;
                    assertTrue(seen.add(ui.name(item)),"Trait appears in more than one book: "+ui.name(item));
                    assertTrue(effects.contains(ui.lore(item).getFirst()),"Missing effect in book "+book.getValue());
                }
                ui.drag();ui.click(52);overview=ui.current;
                assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
            }
            assertEquals(new HashSet<>(TraitCatalog.ALL.stream().map(TraitCatalog.Entry::name).toList()),seen);
            ui.drag();var before=ui.current;
            ui.click(35);ui.click(51);ui.click(52);ui.click(53);ui.click(54);assertSame(before,ui.current);
        }
    }
    @Test void sharedChallengeEffectsUseOneBookAndDifferentUnitRolesStaySeparate() {
        try(var ui=new MenuFixture(false)) {
            ui.menu.open(ui.player);
            ui.assertGroup("신속한 지휘",TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.SPEED).toList());
            ui.assertGroup("정예 소탕",TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.NORMAL_DAMAGE).toList());
            ui.assertGroup("담금질",TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.ENHANCEMENT).toList());
            ui.assertGroup("전장의 투자",TraitCatalog.ALL.stream().filter(e->e.family()==TraitCatalog.Family.GOLD_INCOME).toList());
            for(var role:AttackRole.values())
                ui.assertGroup(role.label()+" 교본",TraitCatalog.ALL.stream().filter(e->e.role()==role).toList());
        }
    }
    @Test void groupedSelectionRejectsLockedAndStaleClicksReplacesFamilyAndFreezesSession() {
        try(var ui=new MenuFixture(false)) {
            ui.menu.open(ui.player);var overview=ui.current;ui.click("출전 수당");
            ui.click(overview,0); // queued click from the book list must not replace the child GUI
            var first=ui.current;
            var locked=ui.click(0);assertTrue(TraitSelections.load(ui.data).ids().isEmpty());verify(locked).setCancelled(true);
            AchievementStats.reached(ui.data,100);ui.click(0);assertEquals(List.of("round_100"),TraitSelections.load(ui.data).ids());
            ui.click(first,0);assertEquals(List.of("round_100"),TraitSelections.load(ui.data).ids());
            AchievementStats.reached(ui.data,150);ui.click("출전 수당 · 15골드");
            assertEquals(List.of("round_150"),TraitSelections.load(ui.data).ids());
            assertEquals(Material.LIME_DYE,ui.current.getItem(1).getType());
            var session=new GameSession(ui.player,new ArenaMap("a",ui.world,0,64,0,new Grid(6)),CampaignRules.standard());
            assertEquals(45,session.arena.coins());
            when(ui.games.active(ui.player)).thenReturn(true);ui.click(45);
            assertEquals(List.of("round_150"),TraitSelections.load(ui.data).ids());
            when(ui.games.active(ui.player)).thenReturn(false);ui.click(52);
            assertTrue(ui.lore(ui.current.getItem(ui.slot("출전 수당"))).contains("장착: 출전 수당 · 15골드"));
            ui.click(45);assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
            assertEquals(45,session.arena.coins()); // an existing game keeps its loadout snapshot
            ui.click("수비 교본");ui.click("수비 교본 · 4%"); // still locked
            assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
            AchievementStats.reached(ui.data,200);ui.click("수비 교본 · 4%");
            ui.click(52);ui.click("출전 수당");ui.click(0); // only one slot: a different family cannot be added
            assertEquals(List.of("round_200"),TraitSelections.load(ui.data).ids());
            ui.click(52);ui.click("수비 교본");ui.click("수비 교본 · 4%"); // clicking the equipped trait removes it
            assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
        }
    }
    @Test void passiveGroupsKeepAutomaticHighestPromotionAndOpeningEffects() {
        try(var ui=new MenuFixture(false)) {
            AchievementStats.reached(ui.data,350);AchievementStats.add(ui.data,AchievementCatalog.Metric.SESSION,1000);
            var before=TraitSelections.load(ui.data);assertEquals(5,before.passives().size());
            ui.menu.open(ui.player);assertTrue(ui.lore(ui.current.getItem(ui.slot("첫 구매 승급"))).contains("패시브 · 해금한 효과 자동 적용"));
            ui.click("첫 구매 승급");
            assertTrue(ui.lore(ui.current.getItem(0)).contains("상위 패시브 적용 중"));
            assertTrue(ui.lore(ui.current.getItem(2)).contains("패시브 · 자동 적용"));
            ui.click(0);ui.click(2);ui.click(52);ui.click("다가오는 인연");
            for(int i=0;i<4;i++) {assertTrue(ui.lore(ui.current.getItem(i)).contains("패시브 · 자동 적용"));ui.click(i);}
            assertEquals(before.allIds(),TraitSelections.load(ui.data).allIds());
            assertTrue(TraitSelections.load(ui.data).ids().isEmpty());
        }
    }
    @Test void fourthSlotAndChallengeNavigationKeepSeasonAndDuplicateClickGuards() {
        try(var ui=new MenuFixture(false)) {
            AchievementStats.reached(ui.data,1500);ui.menu.open(ui.player);
            assertEquals(Material.BARRIER,ui.current.getItem(48).getType());
            AchievementStats.reachedSeasonOne(ui.data,1500);ui.menu.open(ui.player);
            assertEquals(Material.LIGHT_GRAY_DYE,ui.current.getItem(48).getType());
            ui.click("출전 수당");var detail=ui.current;ui.click(50);var challenge=ui.current;
            ui.click(detail,50);assertSame(challenge,ui.current);ui.drag();
            ui.click(22);assertNotSame(challenge,ui.current);assertNotNull(ui.current.getItem(ui.slot("출전 수당")));
            ui.click(50);when(ui.games.active(ui.player)).thenReturn(true);ui.click(11);
            verify(ui.games,never()).start(any(),anyBoolean());
            when(ui.games.active(ui.player)).thenReturn(false);ui.click(11);ui.click(11);
            assertEquals(1,ui.tasks.size());verify(ui.games,never()).start(any(),anyBoolean());
            ui.tasks.removeFirst().run();verify(ui.games,times(1)).start(ui.player,true);
        }
    }

    private static final class MenuFixture implements AutoCloseable {
        final Player player=mock(Player.class);
        final World world=mock(World.class);
        final org.bukkit.persistence.PersistentDataContainer data=TraitSelectionsTest.data();
        final GameService games=mock(GameService.class);
        final MomaPlugin plugin=mock(MomaPlugin.class);
        final TraitMenu menu;
        final List<Runnable> tasks=new ArrayList<>();
        final Map<ItemStack,String> names=new IdentityHashMap<>();
        final Map<ItemStack,List<String>> descriptions=new IdentityHashMap<>();
        final MockedStatic<Bukkit> bukkit;
        final MockedConstruction<ItemStack> items;
        Inventory current;
        MenuFixture(boolean cabin) {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.hasPermission("moma.play")).thenReturn(true);
            when(player.getPersistentDataContainer()).thenReturn(data);when(player.getLocation()).thenReturn(new Location(world,0,64,0));
            when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);when(player.isOnline()).thenReturn(true);
            var lobby=cabin?null:mock(Lobby.class);
            if(cabin)when(games.quizCabinOwner(player)).thenReturn(true);else when(lobby.contains(any())).thenReturn(true);
            menu=new TraitMenu(plugin,games,lobby);
            var view=mock(InventoryView.class);when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenAnswer(c->current);
            doAnswer(c->{current=c.getArgument(0);return null;}).when(player).openInventory(any(Inventory.class));
            doAnswer(c->{current=null;return null;}).when(player).closeInventory();
            bukkit=mockStatic(Bukkit.class);
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(Component.class))).thenAnswer(c->{
                var inv=mock(Inventory.class);when(inv.getHolder()).thenReturn(c.getArgument(0));
                var contents=new ItemStack[(int)c.getArgument(1)];
                doAnswer(call->{contents[call.getArgument(0)]=call.getArgument(1);return null;}).when(inv).setItem(anyInt(),any(ItemStack.class));
                when(inv.getItem(anyInt())).thenAnswer(call->contents[call.getArgument(0)]);return inv;
            });
            var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c->{tasks.add(c.getArgument(1));return null;});
            items=mockConstruction(ItemStack.class,(item,c)->{
                var meta=mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);when(item.getType()).thenReturn((Material)c.arguments().getFirst());
                doAnswer(call->{names.put(item,plain(call.getArgument(0)));return null;}).when(meta).displayName(any(Component.class));
                doAnswer(call->{List<Component> lines=call.getArgument(0);descriptions.put(item,lines.stream().map(MenuFixture::plain).toList());return null;}).when(meta).lore(anyList());
            });
        }
        static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
        String name(ItemStack item){return names.get(item);}
        List<String> lore(ItemStack item){return descriptions.get(item);}
        int slot(String name) {
            for(int slot=0;slot<36;slot++)if(name.equals(name(current.getItem(slot))))return slot;
            throw new AssertionError("Missing menu item: "+name);
        }
        void click(String name){click(slot(name));}
        InventoryClickEvent click(int slot){return click(current,slot);}
        InventoryClickEvent click(Inventory source,int slot) {
            var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(source);
            var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);when(event.getClick()).thenReturn(ClickType.LEFT);when(event.getRawSlot()).thenReturn(slot);
            menu.click(event);return event;
        }
        void drag() {
            var view=player.getOpenInventory();var event=mock(InventoryDragEvent.class);when(event.getView()).thenReturn(view);
            menu.drag(event);verify(event).setCancelled(true);
        }
        void assertGroup(String name,List<TraitCatalog.Entry> expected) {
            click(name);var actual=new ArrayList<String>();
            for(int slot=0;slot<36;slot++)if(current.getItem(slot)!=null)actual.add(name(current.getItem(slot)));
            assertEquals(expected.stream().map(TraitCatalog.Entry::name).toList(),actual);click(52);
        }
        @Override public void close(){items.close();bukkit.close();}
    }
}
