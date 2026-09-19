package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

final class EntityAdapter {
    private final NamespacedKey factionKey, arenaKey, ownerKey;
    private final Set<UUID> moving = new HashSet<>();
    EntityAdapter(MomaPlugin plugin) {
        factionKey = new NamespacedKey(plugin, "faction"); arenaKey = new NamespacedKey(plugin, "arena"); ownerKey = new NamespacedKey(plugin, "owner");
    }
    boolean managed(Entity entity) { return entity.getPersistentDataContainer().has(factionKey, PersistentDataType.STRING); }
    boolean moving(Entity entity) { return moving.contains(entity.getUniqueId()); }
    UUID spawnDefender(ArenaMap map, UUID owner, UnitType type, Rarity rarity, Cell cell) {
        return spawn(map, owner, EntityType.valueOf(type.name()), Faction.DEFENDER, map.location(cell.point()),
                Component.text("[아군] [" + rarity.label() + "] " + type.label(), rarityColor(rarity))).getUniqueId();
    }
    UUID spawnEnemy(ArenaMap map, UUID owner, EnemyType type, boolean boss) {
        return spawn(map, owner, EntityType.valueOf(type.name()), Faction.ENEMY, map.location(map.grid().route().at(0)),
                Component.text(boss ? "[적·보스] " + type.label() : "[적] " + type.label(), NamedTextColor.RED)).getUniqueId();
    }
    private LivingEntity spawn(ArenaMap map, UUID owner, EntityType type, Faction faction, Location location, Component label) {
        Entity entity = map.world().spawn(location, type.getEntityClass(), false, raw -> {
            if (!(raw instanceof LivingEntity living)) throw new IllegalArgumentException("Expected living unit");
            living.setAI(false); living.setGravity(false); living.setInvulnerable(true); living.setSilent(true);
            living.setCollidable(false); living.setPersistent(false); living.setRemoveWhenFarAway(false); living.setCanPickupItems(false);
            living.setFireTicks(0); living.customName(label); living.setCustomNameVisible(true);
            if (living.getEquipment() != null) living.getEquipment().clear();
            if (living instanceof Ageable ageable) { ageable.setAdult(); ageable.setAgeLock(true); }
            if (living instanceof Hoglin hoglin) hoglin.setImmuneToZombification(true);
            if (living instanceof PiglinAbstract piglin) piglin.setImmuneToZombification(true);
            if (living instanceof AbstractCubeMob cube) cube.setSize(1);
            if (living instanceof Zombie zombie) { zombie.setBaby(false); zombie.setShouldBurnInDay(false); }
            if (living instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
            if (living instanceof Vex vex) vex.setLimitedLifetime(false);
            if (type == EntityType.GHAST && living.getAttribute(Attribute.SCALE) != null) living.getAttribute(Attribute.SCALE).setBaseValue(0.45);
            living.getPersistentDataContainer().set(factionKey, PersistentDataType.STRING, faction.name());
            living.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, map.id());
            living.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
        if (!entity.isValid()) {
            entity.remove();
            throw new IllegalStateException("Entity spawn was cancelled");
        }
        return (LivingEntity) entity;
    }
    void remove(UUID id) { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
    boolean move(UUID id, Location destination) {
        Entity entity = Bukkit.getEntity(id);
        if (entity == null || !entity.isValid()) return false;
        moving.add(id);
        try { return entity.teleport(destination); }
        finally { moving.remove(id); }
    }
    static NamedTextColor rarityColor(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> NamedTextColor.WHITE;
            case RARE -> NamedTextColor.GREEN;
            case ANCIENT -> NamedTextColor.AQUA;
            case RELIC -> NamedTextColor.BLUE;
            case NARRATIVE -> NamedTextColor.LIGHT_PURPLE;
            case LEGENDARY -> NamedTextColor.GOLD;
            case EPIC -> NamedTextColor.DARK_PURPLE;
            case MYTHIC -> NamedTextColor.RED;
            case PRIMORDIAL -> NamedTextColor.YELLOW;
        };
    }
}
