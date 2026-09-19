package dev.moma.core;

import java.util.UUID;

public final class Defender {
    private final UUID entityId, ownerId;
    private final String arenaId;
    private final UnitType type;
    private final Rarity rarity;
    private Cell cell;
    private long nextAttackTick;
    private UUID lastTarget;
    private int consecutiveHits;

    public Defender(UUID entityId, UUID ownerId, String arenaId, UnitType type, Rarity rarity, Cell cell) {
        this.entityId = entityId; this.ownerId = ownerId; this.arenaId = arenaId;
        this.type = type; this.rarity = rarity; this.cell = cell;
    }
    public UUID entityId() { return entityId; }
    public UUID ownerId() { return ownerId; }
    public String arenaId() { return arenaId; }
    public Faction faction() { return Faction.DEFENDER; }
    public UnitType type() { return type; }
    public Rarity rarity() { return rarity; }
    public Cell cell() { return cell; }
    public Point position() { return cell.point(); }
    public long nextAttackTick() { return nextAttackTick; }
    public int consecutiveHits() { return consecutiveHits; }
    void move(Cell destination) { cell = destination; }
    void attackAt(long tick, int interval) { nextAttackTick = tick + interval; }
    int hitTarget(UUID target) {
        consecutiveHits = target.equals(lastTarget) ? Math.min(5, consecutiveHits + 1) : 1;
        lastTarget = target;
        return consecutiveHits;
    }
}
