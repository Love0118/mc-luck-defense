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
    private long coins;
    private UUID selected;
    public enum Outcome { PLAYING, VICTORY, ENEMY_LIMIT, TIME_LIMIT }
    private Outcome outcome = Outcome.PLAYING;
    private long earnedCoins;
    private final Collection<Defender> defenderView = Collections.unmodifiableCollection(defenders.values());
    private final Collection<Enemy> enemyView = Collections.unmodifiableCollection(enemies.values());

    public Arena(String id, UUID owner, Grid grid, long startingCoins, int enemyLimit) {
        if (startingCoins < 0 || enemyLimit < 1) throw new IllegalArgumentException("Invalid arena settings");
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.grid = Objects.requireNonNull(grid); this.coins = startingCoins; this.enemyLimit = enemyLimit;
    }
    public String id() { return id; }
    public UUID owner() { return owner; }
    public Grid grid() { return grid; }
    public long coins() { return coins; }
    public int enemyLimit() { return enemyLimit; }
    public boolean ended() { return outcome != Outcome.PLAYING; }
    public Outcome outcome() { return outcome; }
    public long earnedCoins() { return earnedCoins; }
    public void finish(Outcome result) { if (!ended() && result != Outcome.PLAYING) outcome = result; }
    public int enemyCount() { return enemies.size(); }
    public int defenderCount() { return defenders.size(); }
    Collection<Defender> defenderView() { return defenderView; }
    Collection<Enemy> enemyView() { return enemyView; }
    public List<Defender> defenders() { return List.copyOf(defenders.values()); }
    public List<Enemy> enemies() { return List.copyOf(enemies.values()); }
    public Optional<Defender> selected() { return Optional.ofNullable(defenders.get(selected)); }
    public boolean hasEntity(UUID id) { return defenders.containsKey(id) || enemies.containsKey(id); }
    private Result access(UUID actor) {
        return !owner.equals(actor) ? Result.NOT_OWNER : ended() ? Result.ENDED : Result.OK;
    }
    public Result summon(UUID actor, SummonRoll roll, Spawner spawner) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (coins < SUMMON_COST) return Result.INSUFFICIENT_COINS;
        Cell cell = grid.placementOrder().stream().filter(c -> defenders.values().stream().noneMatch(d -> d.cell().equals(c))).findFirst().orElse(null);
        if (cell == null) return Result.FULL;
        // Spawn before committing currency/occupancy: an adapter failure cannot consume a purchase.
        UUID entity = Objects.requireNonNull(spawner.spawn(roll.type(), roll.rarity(), cell));
        if (hasEntity(entity)) throw new IllegalArgumentException("Duplicate entity UUID");
        defenders.put(entity, new Defender(entity, owner, id, roll.type(), roll.rarity(), cell));
        coins -= SUMMON_COST;
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
    public void credit(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative credit");
        coins = Math.addExact(coins, amount);
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
                long reward = enemy.claimReward();
                credit(reward);
                earnedCoins += reward;
                dead.add(enemy.entityId());
                iterator.remove();
            }
        }
        return dead;
    }
}
