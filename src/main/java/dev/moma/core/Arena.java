package dev.moma.core;

import java.util.*;

/** Single-threaded game state. Paper calls this only from its server thread. */
public final class Arena implements java.io.Serializable {
    private static final long serialVersionUID=1L;
    public static final long SUMMON_COST = 10;
    public static final int RESERVE_CAPACITY=48;
    public enum Result { OK, NOT_OWNER, ENDED, INSUFFICIENT_COINS, FULL, INVALID_CELL, OCCUPIED, NO_SELECTION, NOT_SELLABLE }
    /** A null cell places the unit in the non-combat reserve. */
    @FunctionalInterface public interface Spawner { UUID spawn(UnitType type, Rarity rarity, Cell cell); }
    private final String id;
    private final UUID owner;
    private final Grid grid;
    private final int enemyLimit;
    private final boolean smallForce;
    private final LinkedHashMap<UUID, Defender> defenders = new LinkedHashMap<>();
    private LinkedHashMap<UUID, Defender> reserve=new LinkedHashMap<>();
    private List<Rarity> lastPromotions=List.of();
    private boolean acquisitionOrderRecorded=true;
    private SummonRoll pendingRoll;
    private final LinkedHashMap<UUID, Enemy> enemies = new LinkedHashMap<>();
    private long coinUnits;
    private UUID selected;
    private int openingDraws;
    private boolean openingHit;
    private boolean openingTraitHit;
    private EnumSet<Rarity> openingTraitHits=EnumSet.noneOf(Rarity.class);
    private long spentGold;
    private int incomeRemainder;
    private boolean mergingDisabled;
    private final TraitLoadout traits;
    private final java.util.random.RandomGenerator traitRandom;
    private long purchases;
    private boolean lastPurchaseMerged;
    private final double[] roleDamage=new double[AttackRole.values().length];
    private SummonTier summonTier=SummonTier.NORMAL;
    private Defender lastSummoned;
    private final List<UUID> mergedEntities=new ArrayList<>();
    public SummonTier summonTier() { return summonTier; }
    public long summonCost() { return summonTier.cost(); }
    public void reachedRound(int round) {
        SummonTier next=SummonTier.atRound(round);
        if(next.ordinal()>summonTier.ordinal()){summonTier=next;pendingRoll=null;}
    }
    SummonRoll pendingRoll() { return pendingRoll; }
    public Defender lastSummoned() { return lastSummoned; }
    public TraitLoadout traits() { return traits; }
    public boolean lastPurchaseMerged() { return lastPurchaseMerged; }
    public boolean mergingEnabled() { return !mergingDisabled; }
    public Result toggleMerging(UUID actor) {
        Result access=access(actor);
        if(access==Result.OK)mergingDisabled=!mergingDisabled;
        return access;
    }
    public Rarity summonRarity(Rarity original) { return traits.summonedRarity(original,purchases); }
    public boolean criticalAttack() {
        int chance=traits.value(TraitCatalog.Family.CRITICAL);
        return chance>0 && traitRandom.nextInt(100)<chance;
    }
    void recordDamage(AttackRole role,double effective) { roleDamage[role.ordinal()]+=effective; }
    public boolean roleAchievement(AttackRole role) {
        double total=Arrays.stream(roleDamage).sum();
        return total>0 && roleDamage[role.ordinal()]>=total*.70;
    }
    public List<UUID> collectMergedEntities() { var result=List.copyOf(mergedEntities);mergedEntities.clear();return result; }
    public boolean openingBonusActive() { return summonTier==SummonTier.NORMAL && openingDraws < 3 && !openingHit; }
    public int openingDrawsRemaining() { return openingBonusActive() ? 3 - openingDraws : 0; }
    public boolean openingTraitActive(Rarity rarity) { return summonTier==SummonTier.NORMAL && openingDraws<3 && !openingTraitHits.contains(rarity) && traits.openingWeight(rarity)>0; }
    public boolean openingTraitActive() { return Arrays.stream(Rarity.values()).anyMatch(this::openingTraitActive); }
    public int openingTraitRemaining() { return openingTraitActive()?3-openingDraws:0; }
    public int summonWeight(Rarity rarity) {
        int weight=summonTier.weight(rarity,openingBonusActive());
        if(openingTraitActive(rarity))return Math.max(weight,traits.openingWeight(rarity));
        if(rarity==Rarity.COMMON)for(Rarity target:Rarity.values())if(openingTraitActive(target))
            weight-=Math.max(0,traits.openingWeight(target)-summonTier.weight(target,openingBonusActive()));
        return weight;
    }
    public Rarity rarityFromRoll(int roll) {
        if(roll<0 || roll>=Rarity.TOTAL_WEIGHT)throw new IllegalArgumentException("Invalid rarity roll");
        int boundary=0;
        for(Rarity rarity:Rarity.values()){boundary+=summonWeight(rarity);if(roll<boundary)return rarity;}
        throw new IllegalStateException("Rarity weights do not sum to 100000");
    }
    public enum Outcome { PLAYING, VICTORY, ENEMY_LIMIT, TIME_LIMIT }
    private Outcome outcome = Outcome.PLAYING;
    private long earnedUnits;
    private transient Collection<Defender> defenderView = Collections.unmodifiableCollection(defenders.values());
    private transient Collection<Enemy> enemyView = Collections.unmodifiableCollection(enemies.values());

    private void readObject(java.io.ObjectInputStream input)throws java.io.IOException,ClassNotFoundException {
        input.defaultReadObject();
        if(reserve==null)reserve=new LinkedHashMap<>();
        if(lastPromotions==null)lastPromotions=List.of();
        if(!acquisitionOrderRecorded) {
            long order=0;for(Defender d:units())d.acquisitionOrder(order++);
            acquisitionOrderRecorded=true;
        }
        if(openingTraitHits==null) {
            openingTraitHits=EnumSet.noneOf(Rarity.class);
            if(openingTraitHit && traits.openingTarget()!=null)openingTraitHits.add(traits.openingTarget());
        }
        defenderView=Collections.unmodifiableCollection(defenders.values());enemyView=Collections.unmodifiableCollection(enemies.values());
    }
    public Arena(String id, UUID owner, Grid grid, long startingCoins, int enemyLimit) {
        this(id,owner,grid,startingCoins,enemyLimit,TraitLoadout.EMPTY,new HashRandom(0));
    }
    public Arena(String id, UUID owner, Grid grid, long startingCoins, int enemyLimit, TraitLoadout traits,
                 java.util.random.RandomGenerator traitRandom) {
        this(id,owner,grid,startingCoins,enemyLimit,traits,traitRandom,false);
    }
    public Arena(String id, UUID owner, Grid grid, long startingCoins, int enemyLimit, TraitLoadout traits,
                 java.util.random.RandomGenerator traitRandom,boolean smallForce) {
        if (startingCoins < 0 || enemyLimit < 1) throw new IllegalArgumentException("Invalid arena settings");
        this.traits=Objects.requireNonNull(traits);this.traitRandom=Objects.requireNonNull(traitRandom);
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.grid = Objects.requireNonNull(grid);
        this.smallForce=smallForce;
        this.coinUnits = Gold.units(Math.addExact(startingCoins,traits.value(TraitCatalog.Family.START_GOLD))); this.enemyLimit = enemyLimit;
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
    public boolean smallForce() { return smallForce; }
    public int deploymentLimit() { return smallForce?Math.min(10,grid.size()*grid.size()):grid.size()*grid.size(); }
    public int reserveCount() { return reserve.size(); }
    public int unitCount() { return defenders.size()+reserve.size(); }
    public List<Defender> reserveUnits() { return List.copyOf(reserve.values()); }
    public List<Defender> units() { var all=new ArrayList<>(defenders.values());all.addAll(reserve.values());return all; }
    public List<Rarity> lastPromotions() { return lastPromotions; }
    public boolean hasSummonSpace() { return mergingEnabled() || unitCount()<deploymentLimit()+RESERVE_CAPACITY; }
    private Defender unit(UUID id) { Defender d=defenders.get(id);return d==null?reserve.get(id):d; }
    private void removeUnit(UUID id) { defenders.remove(id);reserve.remove(id); }
    public void bindEntity(UUID previous,UUID actual) {
        Defender d=defenders.get(previous);
        if(d==null || hasEntity(actual))throw new IllegalArgumentException("Invalid entity binding");
        defenders.remove(previous);d.bindEntity(actual);defenders.put(actual,d);
        if(previous.equals(selected))selected=actual;
    }
    Collection<Defender> defenderView() { return defenderView; }
    Collection<Enemy> enemyView() { return enemyView; }
    public List<Defender> defenders() { return List.copyOf(defenders.values()); }
    public List<Enemy> enemies() { return List.copyOf(enemies.values()); }
    /** Read-only live views for the server thread; do not structurally mutate during iteration. */
    public Collection<Defender> activeDefenders() { return defenderView; }
    public Collection<Enemy> activeEnemies() { return enemyView; }
    public Optional<Defender> selected() { return Optional.ofNullable(unit(selected)); }
    public boolean hasEntity(UUID id) { return defenders.containsKey(id) || reserve.containsKey(id) || enemies.containsKey(id); }
    private Result access(UUID actor) {
        return !owner.equals(actor) ? Result.NOT_OWNER : ended() ? Result.ENDED : Result.OK;
    }
    public Result summon(UUID actor, SummonRoll roll, Spawner spawner) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (coinUnits < Gold.units(summonCost())) return Result.INSUFFICIENT_COINS;
        if(pendingRoll!=null && !pendingRoll.equals(roll))return Result.FULL;
        Cell cell = defenders.size()>=deploymentLimit()?null:grid.placementOrder(roll.type().role()).stream().filter(c -> defenders.values().stream().noneMatch(d -> d.cell().equals(c))).findFirst().orElse(null);
        Rarity grade=summonRarity(roll.rarity());
        Defender duplicate=mergingEnabled()?units().stream().filter(d->d.type()==roll.type() && d.rarity()==grade).findFirst().orElse(null):null;
        if(duplicate==null && cell==null && reserve.size()>=RESERVE_CAPACITY){pendingRoll=roll;return Result.FULL;}
        if(duplicate!=null) {
            duplicate.merge(summonTier.saleValue(roll.rarity()));
            Defender match;
            while((match=matchingOther(duplicate))!=null) {
                duplicate.absorb(match);removeUnit(match.entityId());mergedEntities.add(match.entityId());
                if(match.entityId().equals(selected))selected=duplicate.entityId();
            }
            lastSummoned=duplicate;
        }
        else {
            // Spawn before committing currency/occupancy: an adapter failure cannot consume a purchase.
            UUID entity = Objects.requireNonNull(spawner.spawn(roll.type(), grade, cell));
            if (hasEntity(entity)) throw new IllegalArgumentException("Duplicate entity UUID");
            lastSummoned=new Defender(entity, owner, id, roll.type(), grade, cell,
                    traits.value(TraitCatalog.Family.ENHANCEMENT)/100.0,summonTier.saleValue(roll.rarity()));
            lastSummoned.acquisitionOrder(purchases);
            (cell==null?reserve:defenders).put(entity, lastSummoned);
        }
        var promoted=new ArrayList<Rarity>();
        for(int i=grade.ordinal()+1;i<=lastSummoned.rarity().ordinal();i++)promoted.add(Rarity.values()[i]);
        lastPromotions=List.copyOf(promoted);
        pendingRoll=null;
        coinUnits -= Gold.units(summonCost());
        spentGold=spentGold>Long.MAX_VALUE-summonCost()?Long.MAX_VALUE:spentGold+summonCost();
        purchases++;lastPurchaseMerged=duplicate!=null;
        if (openingDraws < 3) {
            openingDraws++;
            openingHit |= roll.rarity() == Rarity.ANCIENT || roll.rarity() == Rarity.RELIC;
            openingTraitHit |= roll.rarity()==traits.openingTarget();
            openingTraitHits.add(roll.rarity());
        }
        return Result.OK;
    }
    public long spentGold() { return spentGold; }
    public double damageMultiplier(AttackRole role,boolean boss) {
        return traits.damageMultiplier(role,boss);
    }
    public UnitType summonType(UnitType original,Rarity rawGrade) {
        int chance=traits.value(TraitCatalog.Family.DUPLICATE_ODDS);
        if(chance==0)return original;
        Rarity grade=summonRarity(rawGrade);
        if(grade.ordinal()<Rarity.LEGENDARY.ordinal())return original;
        Defender target=null;
        // Equal enhancements keep the first acquired tower as the single bonus recipient.
        for(Defender d:units())if(d.rarity()==grade && (target==null || d.enhancement()>target.enhancement()
                || d.enhancement()==target.enhancement() && d.acquisitionOrder()<target.acquisitionOrder()))target=d;
        // The original draw contributes (100-chance)/24 to every type, including the target.
        return target!=null && traitRandom.nextInt(100)<chance?target.type():original;
    }
    private Defender matchingOther(Defender unit) {
        return units().stream().filter(d->d!=unit && d.type()==unit.type() && d.rarity()==unit.rarity()).findFirst().orElse(null);
    }
    public Result select(UUID actor, UUID entity) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        if (unit(entity)==null) return Result.NOT_OWNER;
        selected = entity;
        return Result.OK;
    }
    public Result clearSelection(UUID actor) {
        Result access=access(actor);
        if(access==Result.OK)selected=null;
        return access;
    }
    public Result swapSelected(UUID actor, UUID targetId) {
        Result access=access(actor);if(access!=Result.OK)return access;
        Defender first=unit(selected),second=unit(targetId);
        if(first==null)return Result.NO_SELECTION;
        if(second==null)return Result.NOT_OWNER;
        if(first==second){selected=null;return Result.OK;}
        Cell firstCell=first.cell(),secondCell=second.cell();
        var reordered=new LinkedHashMap<UUID,Defender>();
        for(Defender d:reserve.values()) {
            Defender replacement=d==first?second:d==second?first:d;
            reordered.put(replacement.entityId(),replacement);
        }
        first.move(secondCell);second.move(firstCell);
        defenders.remove(first.entityId());defenders.remove(second.entityId());
        if(first.deployed())defenders.put(first.entityId(),first);
        if(second.deployed())defenders.put(second.entityId(),second);
        reserve.clear();reserve.putAll(reordered);
        selected=null;
        return Result.OK;
    }
    public Result moveSelected(UUID actor, Cell destination) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        Defender defender = unit(selected);
        if (defender == null) return Result.NO_SELECTION;
        if (!grid.contains(destination)) return Result.INVALID_CELL;
        Defender occupying=defenders.values().stream().filter(d->d.cell().equals(destination)).findFirst().orElse(null);
        if(occupying!=null && defender.deployed())return Result.OCCUPIED;
        if(!defender.deployed()) {
            if(occupying==null && defenders.size()>=deploymentLimit())return Result.FULL;
            reserve.remove(defender.entityId());
            if(occupying!=null){defenders.remove(occupying.entityId());occupying.move(null);reserve.put(occupying.entityId(),occupying);}
            defenders.put(defender.entityId(),defender);
        }
        defender.move(destination);
        selected = null;
        return Result.OK;
    }
    public Result sellSelected(UUID actor) {
        Result access = access(actor);
        if (access != Result.OK) return access;
        Defender defender = unit(selected);
        if (defender == null) return Result.NO_SELECTION;
        if (defender.rarity().salePrice().isEmpty()) return Result.NOT_SELLABLE;
        credit(defender.saleValue());
        removeUnit(selected);
        selected = null;
        return Result.OK;
    }
    public Result benchSelected(UUID actor) {
        Result access=access(actor);if(access!=Result.OK)return access;
        Defender d=unit(selected);if(d==null)return Result.NO_SELECTION;
        if(!d.deployed())return Result.OK;
        if(reserve.size()>=RESERVE_CAPACITY)return Result.FULL;
        defenders.remove(d.entityId());d.move(null);reserve.put(d.entityId(),d);selected=null;return Result.OK;
    }
    public Result rearrange(UUID actor,Map<UUID,Cell> layout) {
        Result result=validateLayout(actor,layout);if(result!=Result.OK)return result;
        List<Defender> all=units();
        defenders.clear();reserve.clear();
        for(Defender d:all){Cell cell=layout.get(d.entityId());d.move(cell);(cell==null?reserve:defenders).put(d.entityId(),d);}
        return Result.OK;
    }
    public Result validateLayout(UUID actor,Map<UUID,Cell> layout) {
        Result access=access(actor);if(access!=Result.OK)return access;
        if(layout.size()>deploymentLimit() || unitCount()-layout.size()>RESERVE_CAPACITY)return Result.FULL;
        for(UUID id:layout.keySet())if(unit(id)==null)return Result.NOT_OWNER;
        Set<Cell> destinations=new HashSet<>();
        for(Cell cell:layout.values()) {
            if(!grid.contains(cell))return Result.INVALID_CELL;
            if(!destinations.add(cell))return Result.OCCUPIED;
        }
        return Result.OK;
    }
    public record BulkSale(Result result, List<UUID> entities, long income) {}
    /** Exactly this grade; validates the whole sale before mutating currency or units. */
    public BulkSale sellRarity(UUID actor, Rarity rarity) {
        Result access = access(actor);
        if (access != Result.OK) return new BulkSale(access, List.of(), 0);
        Objects.requireNonNull(rarity);
        if (!rarity.autoSellable()) return new BulkSale(Result.NOT_SELLABLE, List.of(), 0);
        var sold = units().stream().filter(d -> d.rarity() == rarity).map(Defender::entityId).toList();
        long income = sold.stream().map(this::unit).mapToLong(Defender::saleValue).reduce(0,Math::addExact);
        credit(income);
        sold.forEach(this::removeUnit);
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
                int bonus=traits.value(TraitCatalog.Family.GOLD_INCOME);
                long fraction=(reward%100)*bonus+incomeRemainder;
                long extra=Math.addExact(Math.multiplyExact(reward/100,bonus),fraction/100);
                incomeRemainder=(int)(fraction%100);
                reward=Math.addExact(reward,extra);
                coinUnits = Math.addExact(coinUnits, reward);
                earnedUnits = Math.addExact(earnedUnits, reward);
                dead.add(enemy.entityId());
                iterator.remove();
            }
        }
        return dead;
    }
}
