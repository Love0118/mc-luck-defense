package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityAdapterTest {
    private EntityAdapter adapter() {
        MomaPlugin plugin = mock(MomaPlugin.class);
        when(plugin.namespace()).thenReturn("momadefense");
        return new EntityAdapter(plugin);
    }
    @Test void cancelledServerSpawnCannotConsumeCurrencyOrOccupyACell() {
        EntityAdapter adapter = adapter();
        World world = mock(World.class); LivingEntity entity = mock(LivingEntity.class);
        doReturn(entity).when(world).spawn(any(Location.class), eq(EntityType.WOLF.getEntityClass()), eq(false), any());
        when(entity.isValid()).thenReturn(false);
        UUID owner = UUID.randomUUID();
        ArenaMap map = new ArenaMap("a", world, 0, 64, 0, new Grid(3));
        Arena arena = new Arena("a", owner, map.grid(), 10, 100);
        assertThrows(IllegalStateException.class, () -> arena.summon(owner, new SummonRoll(UnitType.WOLF, Rarity.COMMON),
                (type, rarity, cell) -> adapter.spawnDefender(map, owner, type, rarity, cell)));
        assertEquals(10, arena.coins()); assertTrue(arena.defenders().isEmpty()); verify(entity).remove();
    }
    @Test void movementAuthorizationExistsOnlyDuringOwnTeleportEvenOnFailure() {
        EntityAdapter adapter = adapter();
        LivingEntity entity = mock(LivingEntity.class); UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id); when(entity.isValid()).thenReturn(true);
        Location location = new Location(mock(World.class), 1, 65, 1);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(entity);
            when(entity.teleport(location)).thenAnswer(invocation -> {
                assertTrue(adapter.moving(entity)); throw new IllegalStateException("Rejected teleport");
            });
            assertFalse(adapter.moving(entity));
            assertThrows(IllegalStateException.class, () -> adapter.move(id, location));
            assertFalse(adapter.moving(entity));
        }
    }
}
