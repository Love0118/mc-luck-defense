package dev.moma.paper;

import dev.moma.core.*;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OddsItemTest {
    @Test void openingTraitOddsMatchDrawTableAndDisappearAfterTargetHit() {
        var arena=new Arena("a",java.util.UUID.randomUUID(),new Grid(6),30,100,
                new TraitLoadout(List.of("session_1000")),new HashRandom(1));
        ItemMeta meta=mock(ItemMeta.class);
        try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {
            ShopMenu.oddsItem(arena);
            arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.MYTHIC),(t,r,c)->java.util.UUID.randomUUID());
            ShopMenu.oddsItem(arena);
            var lore=org.mockito.ArgumentCaptor.forClass(List.class);verify(meta,times(2)).lore(lore.capture());
            String first=lore.getAllValues().getFirst().toString(),last=lore.getAllValues().getLast().toString();
            assertTrue(first.contains("4%"));assertTrue(first.contains("16.381%"));assertTrue(first.contains("인연 보정"));
            assertTrue(last.contains("0.08%"));assertFalse(last.contains("인연 보정"));
        }
    }
    @Test void round101ChangesBookAppearanceAndShowsOnlyAdvancedPool() {
        var arena=new Arena("a",java.util.UUID.randomUUID(),new Grid(6),30,100);
        var materials=new java.util.ArrayList<org.bukkit.Material>();var metas=new java.util.ArrayList<ItemMeta>();
        try(var items=mockConstruction(ItemStack.class,(item,context)->{
            materials.add((org.bukkit.Material)context.arguments().getFirst());
            ItemMeta meta=mock(ItemMeta.class);metas.add(meta);when(item.getItemMeta()).thenReturn(meta);
        })) {
            arena.reachedRound(100);ShopMenu.oddsItem(arena);
            arena.reachedRound(101);ShopMenu.oddsItem(arena);
            assertEquals(List.of(org.bukkit.Material.KNOWLEDGE_BOOK,org.bukkit.Material.ENCHANTED_BOOK),materials);
            var name=org.mockito.ArgumentCaptor.forClass(Component.class);verify(metas.getLast()).displayName(name.capture());
            assertTrue(LegacyComponentSerializer.legacyAmpersand().serialize(name.getValue()).contains("&d&l후반 소환 확률"));
            var lore=org.mockito.ArgumentCaptor.forClass(List.class);verify(metas.getLast()).lore(lore.capture());
            String text=lore.getValue().toString();
            for(String expected:List.of("32.01%","35%","30%","2%","0.8%","0.19%","100골드"))assertTrue(text.contains(expected));
            for(String absent:List.of("일반","레어","고대","초반 보정"))assertFalse(text.contains(absent));
        }
    }
    @Test void openingIconShowsBoostAndReturnsToNormalAfterHit() {
        var arena=new Arena("a",java.util.UUID.randomUUID(),new Grid(6),30,100);
        ItemMeta meta=mock(ItemMeta.class);
        try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {
            ShopMenu.oddsItem(arena);
            arena.summon(arena.owner(),new SummonRoll(UnitType.WOLF,Rarity.RELIC),(t,r,c)->java.util.UUID.randomUUID());
            ShopMenu.oddsItem(arena);
            var lore=org.mockito.ArgumentCaptor.forClass(List.class);verify(meta,times(2)).lore(lore.capture());
            String opening=lore.getAllValues().getFirst().toString(),normal=lore.getAllValues().getLast().toString();
            assertTrue(opening.contains("30%"));assertTrue(opening.contains("15%"));assertTrue(opening.contains("20.301%"));
            assertTrue(normal.contains("10.2%"));assertTrue(normal.contains("5.1%"));assertFalse(normal.contains("초반 보정"));
        }
    }
    @Test void iconShowsAllNineExactProbabilitiesIncludingTheRarestGrade() {
        ItemMeta meta=mock(ItemMeta.class);
        try(var items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(meta))) {
            ShopMenu.oddsItem();
            @SuppressWarnings("unchecked") var lore=org.mockito.ArgumentCaptor.forClass(List.class);
            verify(meta).lore(lore.capture());
            String[] expected={"50.001%","33.1%","10.2%","5.1%","0.8%","0.5%","0.2%","0.08%","0.019%"};
            for(int i=0;i<9;i++) {
                String line=LegacyComponentSerializer.legacyAmpersand().serialize((Component)lore.getValue().get(i));
                assertTrue(line.contains(Rarity.values()[i].label()));assertTrue(line.contains(expected[i]));
            }
            assertTrue(LegacyComponentSerializer.legacyAmpersand().serialize((Component)lore.getValue().get(9)).contains("1/24"));
        }
    }
}
