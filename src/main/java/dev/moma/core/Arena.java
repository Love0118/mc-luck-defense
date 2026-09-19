package dev.moma.core;

import java.util.*;

/** Single-threaded game state. Paper calls this only from its server thread. */
public final class Arena {
    public static final long SUMMON_COST = 10;
    public enum Result { OK, NOT_OWNER, ENDED, INSUFFICIENT_COINS, FULL, INVALID_CELL, OCCUPIED, NO_SELECTION, NOT_SELLABLE }
    @FunctionalInterface public interface Spawner { UUID spawn(UnitType type, Rarity rarity, Cell cell); }
    private final String id;
    private final UUID owner;
    private final Grid grid;
    private final int enemyLimit;
    private final LinkedHashMap<UUID, Defender> defenders = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Enemy> enemies = new LinkedHashMap<>();
    private long coinUnits;
    private UUID selected;
    public enum Outcome { PLAYING, VICTORY, ENEMY_LIMIT, TIME_LIMIT }
    private Outcome outcome = Outcome.PLAYING;
    private long earnedUnits;
    private final Collection<Defender> defenderView = Collections.unmodifiableCollection(defenders.values());
    private final Collection<Enemy> enemyView = Collections.unmodifiableCollection(enemies.values());

    public Arena(String id, UUID owner, Grid grid, long startingCoins, int enemyLimit) {
        if (startingCoins < 0 || enemyLimit < 1) throw new IllegalArgumentException("Invalid arena settings");
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.grid = Objects.requireNonNull(grid); this.coinUnits = Gold.units(startingCoins); this.enemyLimit = enemyLimit;
    }
    public String id() { return id; }
    public UUID owner() { return owner; }
    public Grid grid() { return grid; }
    public double coins() { return Gold.amount(coinUnits); }
    public int enemyLimit() { return enemyLimit; }
    public boolean ended() { return outcome != Outcome.PLAYING; }
    public Outcome outcome() { return outcome; }
    public double earnedCoins() { return Gold.amount(earnedUnits); }
    public void finish(Outcome result) { if (!ended() && result != Outcome.PLAYING) outcome = result; }
    public int enemyCount() { return enemies.size(); }
    public int defenderCount() { return defenders.size(); }
    Collection<Defender> defenderView() { return defenderView; }
    Collection<Enemy> enemyView() { return enemyView; }
    public List<Defender> defenders() { return List.copyOf(defenders.values()); }
    public List<Enemy> enemies() { return List.copyOf(enemies.values()); }
    /** Read-only live views for the server thread; do not structurally mutate during iteration. */
    public Collection<Defender> activeDefenders() { return defenderView; }
    public Collection<Enemy> activeEnemies() { return enemyView; }
    public Optional<Defender> selected() { return Optional.ofNullable(defenders.get(selected)); }
    public boolean hasEntity(UUID id) { return defenders.containsKey(id) || enemies.containsKey(id); }
    private Result access(UUID actor) {
        return !owner.equals(actor) ? Result.NOT_OWNER : ended() ? Result.ENDED : Result.OK;
    }
    public Result summon(UUID actor, SummonRoll roll, Spawner spawner) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (coinUnits < Gold.units(SUMMON_COST)) return Result.INSUFFICIENT_COINS;
        Cell cell = grid.placementOrder(roll.type().role()).stream().filter(c -> defenders.values().stream().noneMatch(d -> d.cell().equals(c))).findFirst().orElse(null);
        if (cell == null) return Result.FULL;
        // Spawn before committing currency/occupancy: an adapter failure cannot consume a purchase.
        UUID entity = Objects.requireNonNull(spawner.spawn(roll.type(), roll.rarity(), cell));
        if (hasEntity(entity)) throw new IllegalArgumentException("Duplicate entity UUID");
        defenders.put(entity, new Defender(entity, owner, id, roll.type(), roll.rarity(), cell));
        coinUnits -= Gold.units(SUMMON_COST);
        return Result.OK;
    }
    public Result select(UUID actor, UUID entity) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (!defenders.containsKey(entity)) return Result.NOT_OWNER;
        selected = entity;
        return Result.OK;
    }
    public Result moveSelected(UUID actor, Cell destination) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        Defender defender = defenders.get(selected);
        if (defender == null) return Result.NO_SELECTION;
        if (!grid.contains(destination)) return Result.INVALID_CELL;
        if (defenders.values().stream().anyMatch(d -> d.cell().equals(destination))) return Result.OCCUPIED;
        defender.move(destination);
        selected = null;
        return Result.OK;
    }
    public Result sellSelected(UUID actor) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        Defender defender = defenders.get(selected);
        if (defender == null) return Result.NO_SELECTION;
        if (defender.rarity().salePrice().isEmpty()) return Result.NOT_SELLABLE;
        credit(defender.rarity().salePrice().getAsInt());
        defenders.remove(selected);
        selected = null;
        return Result.OK;
    }
    /** Apply a complete layout atomically, including swaps on a full board. */
    public Result rearrange(UUID actor, Map<UUID, Cell> layout) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (!layout.keySet().equals(defenders.keySet())) return Result.NOT_OWNER;
        Set<Cell> destinations = new HashSet<>();
        for (Cell cell : layout.values()) {
            if (!grid.contains(cell)) return Result.INVALID_CELL;
            if (!destinations.add(cell)) return Result.OCCUPIED;
        }
        layout.forEach((id, cell) -> defenders.get(id).move(cell));
        return Result.OK;
    }
    public record BulkSale(Result result, List<UUID> entities, long income) {}
    /** Exactly this grade; validates the whole sale before mutating currency or units. */
    public BulkSale sellRarity(UUID actor, Rarity rarity) {
        Result access = access(actor);
        if (access != Result.OK) return new BulkSale(access, List.of(), 0);
        Objects.requireNonNull(rarity);
        if (rarity.salePrice().isEmpty()) return new BulkSale(Result.NOT_SELLABLE, List.of(), 0);
        var sold = defenders.values().stream().filter(d -> d.rarity() == rarity).map(Defender::entityId).toList();
        long income = Math.multiplyExact((long) sold.size(), rarity.salePrice().getAsInt());
        credit(income);
        sold.forEach(defenders::remove);
        if (selected != null && sold.contains(selected)) selected = null;
        return new BulkSale(Result.OK, sold, income);
    }
    public void credit(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative credit");
        coinUnits = Math.addExact(coinUnits, Gold.units(amount));
    }
    public void addEnemy(Enemy enemy) {
        if (ended()) throw new IllegalStateException("Arena ended");
        if (!enemy.arenaId().equals(id) || !enemy.alive() || hasEntity(enemy.entityId())) throw new IllegalArgumentException("Invalid enemy");
        enemies.put(enemy.entityId(), enemy);
        if (enemies.size() >= enemyLimit) outcome = Outcome.ENEMY_LIMIT;
    }
    public List<UUID> collectDeadEnemies() {
        var dead = new ArrayList<UUID>();
        var iterator = enemies.values().iterator();
        while (iterator.hasNext()) {
            Enemy enemy = iterator.next();
            if (!enemy.alive()) {
                long reward = enemy.claimRewardUnits();
                coinUnits = Math.addExact(coinUnits, reward);
                earnedUnits = Math.addExact(earnedUnits, reward);
                dead.add(enemy.entityId());
                iterator.remove();
            }
        }
        return dead;
    }
}
