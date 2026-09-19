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
    public interface MotionBridge { boolean mudMovePresentation(Location location); }
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
            when(entity.getWorld()).thenReturn(location.getWorld());
            when(entity.teleport(location)).thenAnswer(invocation -> {
                assertTrue(adapter.moving(entity)); throw new IllegalStateException("Rejected teleport");
            });
            assertFalse(adapter.moving(entity));
            assertThrows(IllegalStateException.class, () -> adapter.move(id, location));
            assertFalse(adapter.moving(entity));
        }
    }
    @Test void stationaryUnitsAvoidTeleportButDisplacementAndRotationAreRepaired() {
        EntityAdapter adapter = adapter(); LivingEntity entity = mock(LivingEntity.class);
        UUID id = UUID.randomUUID(); World world = mock(World.class);
        Location target = new Location(world, 1, 65, 2);
        when(entity.getUniqueId()).thenReturn(id); when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world); when(entity.getX()).thenReturn(1d);
        when(entity.getY()).thenReturn(65d); when(entity.getZ()).thenReturn(2d);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(entity);
            assertTrue(adapter.move(id, target)); verify(entity, never()).teleport(any(Location.class));
            when(entity.getZ()).thenReturn(2.01d); when(entity.teleport(target)).thenReturn(true);
            assertTrue(adapter.move(id, target)); verify(entity).teleport(target);
            when(entity.getZ()).thenReturn(2d); when(entity.getYaw()).thenReturn(10f);
            assertTrue(adapter.move(id, target)); verify(entity, times(2)).teleport(target);
            when(entity.isValid()).thenReturn(false); assertFalse(adapter.move(id, target));
        }
    }
    @Test void continuousMotionUsesOptionalBridgeAndFallsBackOnRejection() {
        EntityAdapter adapter = adapter();
        LivingEntity entity = mock(LivingEntity.class, withSettings().extraInterfaces(MotionBridge.class));
        UUID id = UUID.randomUUID(); Location destination = new Location(mock(World.class), 2, 65, 3);
        when(entity.isValid()).thenReturn(true); when(entity.getWorld()).thenReturn(destination.getWorld());
        MotionBridge bridge = (MotionBridge) entity;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(entity);
            when(bridge.mudMovePresentation(destination)).thenReturn(true);
            assertTrue(adapter.advance(id, destination)); verify(entity, never()).teleport(any(Location.class));
            when(bridge.mudMovePresentation(destination)).thenReturn(false); when(entity.teleport(destination)).thenReturn(true);
            assertTrue(adapter.advance(id, destination)); verify(entity).teleport(destination);
        }
    }
}
