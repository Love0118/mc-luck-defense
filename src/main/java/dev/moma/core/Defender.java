package dev.moma.core;

import java.util.UUID;

public final class Defender {
    private final UUID entityId, ownerId;
    private final String arenaId;
    private final UnitType type;
    private Rarity rarity;
    private CombatProfile profile;
    private int enhancement;
    private double inheritedDamage;
    private long saleValue;
    private Cell cell;
    private long nextAttackTick;
    private UUID lastTarget;
    private int consecutiveHits;

    public Defender(UUID entityId, UUID ownerId, String arenaId, UnitType type, Rarity rarity, Cell cell) {
        this(entityId,ownerId,arenaId,type,rarity,cell,SummonTier.NORMAL);
    }
    public Defender(UUID entityId, UUID ownerId, String arenaId, UnitType type, Rarity rarity, Cell cell, SummonTier tier) {
        this.entityId = entityId; this.ownerId = ownerId; this.arenaId = arenaId;
        this.type = type; this.rarity = rarity; this.cell = cell; this.profile = type.profile().at(rarity);
        saleValue=tier.saleValue(rarity);
    }
    public UUID entityId() { return entityId; }
    public UUID ownerId() { return ownerId; }
    public String arenaId() { return arenaId; }
    public Faction faction() { return Faction.DEFENDER; }
    public UnitType type() { return type; }
    public Rarity rarity() { return rarity; }
    public CombatProfile profile() { return profile; }
    public int enhancement() { return enhancement; }
    public double damageMultiplier() { return 1.0+enhancement+(enhancement/5)*.5; }
    public long saleValue() { return rarity.salePrice().isEmpty()?0:saleValue; }
    public String label() { return type.label() + (enhancement==0?"":" +"+enhancement); }
    void merge(long incomingSaleValue) {
        saleValue=Math.addExact(saleValue,incomingSaleValue);
        addEnhancement(1,0);
    }
    void absorb(Defender other) {
        if(type!=other.type || rarity!=other.rarity)throw new IllegalArgumentException("Different unit");
        saleValue=Math.addExact(saleValue,other.saleValue());
        nextAttackTick=Math.max(nextAttackTick,other.nextAttackTick);
        addEnhancement(other.enhancement+1,other.inheritedDamage);
    }
    private void addEnhancement(int amount,double inherited) {
        inheritedDamage+=inherited;
        enhancement=Math.addExact(enhancement,amount);
        while(enhancement>=20 && rarity!=Rarity.TRUE_PRIMORDIAL) {
            // Carry the +20 damage forward; promotion must never weaken an existing tower.
            inheritedDamage=Math.max(inheritedDamage+type.profile().at(rarity).damage()*23,
                    type.profile().at(Rarity.values()[rarity.ordinal()+1]).damage());
            rarity=Rarity.values()[rarity.ordinal()+1];enhancement-=20;
            inheritedDamage-=type.profile().at(rarity).damage();
        }
        CombatProfile base=type.profile().at(rarity);
        profile=new CombatProfile(inheritedDamage+base.damage()*damageMultiplier(),base.intervalTicks(),base.range(),base.areaRadius(),base.targets());
    }
    public Cell cell() { return cell; }
    public Point position() { return cell.point(); }
    public long nextAttackTick() { return nextAttackTick; }
    public int consecutiveHits() { return consecutiveHits; }
    UUID lastTarget() { return lastTarget; }
    void move(Cell destination) { cell = destination; }
    void attackAt(long tick, int interval) { nextAttackTick = tick + interval; }
    int hitTarget(UUID target) {
        consecutiveHits = target.equals(lastTarget) ? Math.min(5, consecutiveHits + 1) : 1;
        lastTarget = target;
        return consecutiveHits;
    }
}
