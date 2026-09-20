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
    private final java.util.Map<UUID, Float> defenderYaw = new java.util.HashMap<>();
    private PrivateGlow privateGlow;
    private final Entity[] batchEntities = new Entity[100];
    private final double[] batchPositions = new double[500];
    private java.lang.reflect.Method batchBridge;
    private boolean batchBridgeChecked;
    private static final ClassValue<java.util.Optional<java.lang.reflect.Method>> PRESENTATION_MOTION = new ClassValue<>() {
        @Override protected java.util.Optional<java.lang.reflect.Method> computeValue(Class<?> type) {
            try { return java.util.Optional.of(type.getMethod("mudMovePresentation", Location.class)); }
            catch (NoSuchMethodException absent) { return java.util.Optional.empty(); }
        }
    };
    EntityAdapter(MomaPlugin plugin) {
        factionKey = new NamespacedKey(plugin, "faction"); arenaKey = new NamespacedKey(plugin, "arena"); ownerKey = new NamespacedKey(plugin, "owner");
    }
    void enablePrivateGlow(MomaPlugin plugin) { privateGlow = new PrivateGlow(plugin); }
    void selectGlow(Player player, UUID entity) { if (privateGlow != null) privateGlow.select(player, entity); }
    void close() { if (privateGlow != null) privateGlow.close(); }
    boolean managed(Entity entity) {
        if(entity instanceof ComplexEntityPart part)entity=part.getParent();
        return entity.getPersistentDataContainer().has(factionKey, PersistentDataType.STRING);
    }
    boolean moving(Entity entity) { return moving.contains(entity.getUniqueId()); }
    UUID spawnDefender(ArenaMap map, UUID owner, UnitType type, Rarity rarity, Cell cell) {
        return spawn(map, owner, EntityType.valueOf(type.name()), Faction.DEFENDER, map.location(cell.point()),
                Component.text("[아군] [" + rarity.label() + "] " + type.label(), rarityColor(rarity))).getUniqueId();
    }
    UUID spawnEnemy(ArenaMap map, UUID owner, EnemyType type, boolean boss) {
        LivingEntity enemy=spawn(map, owner, EntityType.valueOf(type.name()), Faction.ENEMY, map.location(map.grid().route().at(0)),
                Component.text(boss ? "[적·보스] " + type.label() : "[적] " + type.label(), NamedTextColor.RED));
        return enemy.getUniqueId();
    }
    void updateDefenderName(Defender defender) {
        Entity entity=Bukkit.getEntity(defender.entityId());
        if(entity!=null)entity.customName(Component.text("[아군] ["+defender.rarity().label()+"] "+defender.label(),rarityColor(defender.rarity())));
    }
    private LivingEntity spawn(ArenaMap map, UUID owner, EntityType type, Faction faction, Location location, Component label) {
        Entity entity = map.world().spawn(location, type.getEntityClass(), false, raw -> {
            if (!(raw instanceof LivingEntity living)) throw new IllegalArgumentException("Expected living unit");
            living.setAI(false); living.setGravity(false); living.setInvulnerable(true); living.setSilent(true);
            living.setNoPhysics(true);
            living.addScoreboardTag("mud_presentation_v1");
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
            if (living instanceof Endermite mite) mite.setLifetimeTicks(0);
            if (living instanceof Wither wither) wither.setInvulnerableTicks(0);
            if (living instanceof EnderDragon dragon) dragon.setPhase(EnderDragon.Phase.HOVER);
            if (living.getAttribute(Attribute.SCALE) != null) living.getAttribute(Attribute.SCALE).setBaseValue(switch (type) {
                case GHAST, WARDEN, IRON_GOLEM, RAVAGER, HOGLIN, POLAR_BEAR, PANDA, WITHER, ENDER_DRAGON, ELDER_GUARDIAN -> 1.0;
                default -> 2.0;
            });
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
    void remove(UUID id) {
        defenderYaw.remove(id);
        if (privateGlow != null) privateGlow.removed(id);
        Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove();
    }
    void face(Defender defender, Point target) {
        Point origin = defender.position();
        if (origin.equals(target)) return;
        float yaw = yawTo(origin, target);
        defenderYaw.put(defender.entityId(), yaw);
        Entity entity = Bukkit.getEntity(defender.entityId());
        if (entity != null && entity.isValid()) rotateDefender(entity, yaw);
    }
    static float yawTo(Point origin, Point target) {
        return Location.normalizeYaw((float) Math.toDegrees(Math.atan2(-(target.x()-origin.x()), target.z()-origin.z())));
    }
    private void rotateDefender(Entity entity, float yaw) {
        if (entity.getYaw() != yaw || entity.getPitch() != 0) entity.setRotation(yaw, 0);
        if (entity instanceof LivingEntity living && living.getBodyYaw() != yaw) living.setBodyYaw(yaw);
    }
    boolean moveDefender(UUID id, Location destination) {
        Entity entity = Bukkit.getEntity(id);
        if (entity == null || !entity.isValid()) return false;
        float yaw = defenderYaw.getOrDefault(id, 0f);
        rotateDefender(entity, yaw);
        destination = destination.clone(); destination.setYaw(yaw); destination.setPitch(0);
        return move(id, destination);
    }
    boolean move(UUID id, Location destination) {
        Entity entity = Bukkit.getEntity(id);
        if (entity == null || !entity.isValid()) return false;
        // Still inspect actual coordinates so external displacement is repaired immediately.
        if (entity.getWorld().equals(destination.getWorld()) && entity.getX() == destination.getX()
                && entity.getY() == destination.getY() && entity.getZ() == destination.getZ()
                && entity.getYaw() == destination.getYaw() && entity.getPitch() == destination.getPitch()) return true;
        moving.add(id);
        try { return entity.teleport(destination); }
        finally { moving.remove(id); }
    }
    /** Continuous enemy motion may use the guarded fork bridge; manual relocation remains teleport. */
    boolean advance(UUID id, Location destination) {
        Entity entity = Bukkit.getEntity(id);
        if (entity == null || !entity.isValid()) return false;
        if(entity instanceof Shulker) return ShulkerMotion.move(entity,destination);
        if (entity instanceof LivingEntity living && living.getBodyYaw() != destination.getYaw()) living.setBodyYaw(destination.getYaw());
        var bridge = PRESENTATION_MOTION.get(entity.getClass());
        if (bridge.isPresent()) {
            try { if ((boolean) bridge.orElseThrow().invoke(entity, destination)) return true; }
            catch (ReflectiveOperationException exception) { throw new IllegalStateException("MUD motion bridge failed", exception); }
        }
        return move(id, destination);
    }
    boolean advanceAll(dev.moma.core.Arena arena, ArenaMap map) {
        if (!batchBridgeChecked) {
            batchBridgeChecked = true;
            try { batchBridge = Bukkit.getServer().getClass().getMethod("mudMovePresentationBatch", Entity[].class, double[].class, int.class); }
            catch (NoSuchMethodException absent) { /* Standard Paper fallback. */ }
        }
        if (batchBridge != null && arena.enemyCount() <= batchEntities.length && arena.activeEnemies().stream().noneMatch(e->e.type()==EnemyType.SHULKER)) {
            int i = 0;
            try {
                for (dev.moma.core.Enemy enemy : arena.activeEnemies()) {
                    Entity entity = Bukkit.getEntity(enemy.entityId());
                    if (entity == null || !entity.isValid() || !entity.getWorld().equals(map.world())) return false;
                    Point point = enemy.position(map.grid().route());
                    batchEntities[i] = entity;
                    batchPositions[i*5] = map.originX()+point.x()+.5;
                    batchPositions[i*5+1] = map.floorY()+1;
                    batchPositions[i*5+2] = map.originZ()+point.z()+.5;
                    float yaw = routeYaw(map.grid().route(), enemy.progress());
                    batchPositions[i*5+3] = yaw; batchPositions[i*5+4] = 0;
                    if (entity instanceof LivingEntity living && living.getBodyYaw() != yaw) living.setBodyYaw(yaw);
                    i++;
                }
                if ((boolean) batchBridge.invoke(Bukkit.getServer(), batchEntities, batchPositions, i)) return true;
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("MUD Java motion batch failed; no retry performed", error);
            } finally { java.util.Arrays.fill(batchEntities, null); }
        }
        boolean intact = true;
        for (dev.moma.core.Enemy enemy : arena.activeEnemies()) {
            Location destination = map.location(enemy.position(map.grid().route()));
            destination.setYaw(routeYaw(map.grid().route(), enemy.progress()));
            intact &= advance(enemy.entityId(), destination);
        }
        return intact;
    }
    static float routeYaw(Route route, double progress) {
        double distance = ((progress % route.length()) + route.length()) % route.length();
        int side = (int) (distance / (route.max()-route.min()));
        return switch (side) { case 0 -> -90; case 1 -> 0; case 2 -> 90; default -> -180; };
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
