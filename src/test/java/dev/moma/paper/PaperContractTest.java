package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperContractTest {
    @Test void everyAppearanceMapsToALivingPaperEntity() {
        for (UnitType type : UnitType.values()) assertTrue(EntityType.valueOf(type.name()).isAlive());
        for (EnemyType type : EnemyType.values()) assertTrue(EntityType.valueOf(type.name()).isAlive());
    }
    @Test void packagedMetadataAndDevelopmentDefaultsAreValid() throws Exception {
        var plugin = load("plugin.yml");
        assertEquals("MCLuckDefense", plugin.getString("name"));
        assertEquals("MC Luck Defense", plugin.getString("prefix"));
        assertEquals(MomaPlugin.class.getName(), plugin.getString("main"));
        assertEquals("26.3", plugin.getString("api-version"));
        assertTrue(plugin.contains("commands.mud"));
        assertFalse(plugin.getString("version").contains("$"));
        CampaignRules settings = CampaignRules.standard();
        assertEquals(6, settings.gridSize()); assertEquals(100, settings.startingCoins());
        assertThrows(IllegalArgumentException.class, () -> settings.withHealthScale(Double.NaN));
        assertFalse(plugin.contains("commands.moma"));
    }
    private YamlConfiguration load(String resource) throws Exception {
        try (var reader = new InputStreamReader(getClass().getClassLoader().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            var config = new YamlConfiguration(); config.load(reader); return config;
        }
    }
    @Test void placementRejectsPathWrongWorldWrongHeightAndGaps() {
        World world = mock(World.class);
        ArenaMap map = new ArenaMap("a", world, 128, 64, 0, new Grid(5));
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world); when(block.getY()).thenReturn(64);
        when(block.getX()).thenReturn(131); when(block.getZ()).thenReturn(6);
        assertEquals(new Cell(1, 2), map.cellAt(block));
        when(block.getX()).thenReturn(125); assertNull(map.cellAt(block));
        when(block.getX()).thenReturn(129); assertNull(map.cellAt(block));
        when(block.getX()).thenReturn(131); when(block.getY()).thenReturn(65); assertNull(map.cellAt(block));
        when(block.getY()).thenReturn(64); when(block.getWorld()).thenReturn(mock(World.class)); assertNull(map.cellAt(block));
        assertNull(map.cellAt(null));
    }
}
