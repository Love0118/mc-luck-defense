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
