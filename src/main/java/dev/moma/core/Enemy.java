package dev.moma.core;

import java.util.ArrayList;
import java.util.UUID;

public final class Enemy {
    private record Slow(double fraction, long expiresAt) {}
    private final UUID entityId;
    private final String arenaId;
    private final EnemyType type;
    private final boolean boss;
    private final double speed;
    private final long rewardUnits;
    private final ArrayList<Slow> slows = new ArrayList<>();
    private double health, progress;
    private double positionProgress = Double.NaN;
    private Route positionRoute;
    private Point position;
    private boolean rewarded;

    public Enemy(UUID entityId, String arenaId, EnemyType type, double health, double speed, double reward, boolean boss) {
        if (!Double.isFinite(health) || health <= 0 || !Double.isFinite(speed) || speed <= 0 || reward < 0)
            throw new IllegalArgumentException("Invalid enemy stats");
        this.entityId = entityId; this.arenaId = arenaId; this.type = type;
        this.health = health; this.speed = speed; this.rewardUnits = Gold.units(reward); this.boss = boss;
    }
    public UUID entityId() { return entityId; }
    public String arenaId() { return arenaId; }
    public EnemyType type() { return type; }
    public Faction faction() { return Faction.ENEMY; }
    public boolean boss() { return boss; }
    public double health() { return health; }
    public double progress() { return progress; }
    public Point position(Route route) {
        if (positionProgress != progress || !route.equals(positionRoute)) {
            position = route.at(progress); positionProgress = progress; positionRoute = route;
        }
        return position;
    }
    public boolean alive() { return health > 0; }
    public void damage(double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid damage");
        health = Math.max(0, health - amount);
    }
    public void slow(double fraction, long expiresAt) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction >= 1) throw new IllegalArgumentException("Invalid slow");
        // A weaker long-lived slow must still apply after a stronger short-lived slow expires.
        slows.removeIf(s -> s.fraction <= fraction && s.expiresAt <= expiresAt);
        if (slows.stream().noneMatch(s -> s.fraction >= fraction && s.expiresAt >= expiresAt))
            slows.add(new Slow(fraction, expiresAt));
    }
    public double slowAt(long tick) {
        if (slows.isEmpty()) return 0;
        slows.removeIf(s -> s.expiresAt <= tick);
        double strongest = 0;
        for (Slow slow : slows) strongest = Math.max(strongest, slow.fraction);
        return strongest;
    }
    void advance(long tick) { if (alive()) progress += speed / 20.0 * (1 - slowAt(tick)); }
    long claimRewardUnits() {
        if (alive() || rewarded) return 0;
        rewarded = true;
        return rewardUnits;
    }
}
