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
