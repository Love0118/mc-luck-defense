package dev.moma.core;

import java.util.*;

/** Single-threaded game state. Paper calls this only from its server thread. */
public final class Arena implements java.io.Serializable {
    private static final long serialVersionUID=1L;
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
    private int openingDraws;
    private boolean openingHit;
    private boolean openingTraitHit;
    private EnumSet<Rarity> openingTraitHits=EnumSet.noneOf(Rarity.class);
    private long spentGold;
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
    public void reachedRound(int round) { if(round>100)summonTier=SummonTier.ADVANCED; }
    public Defender lastSummoned() { return lastSummoned; }
    public TraitLoadout traits() { return traits; }
    public boolean lastPurchaseMerged() { return lastPurchaseMerged; }
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
        if (startingCoins < 0 || enemyLimit < 1) throw new IllegalArgumentException("Invalid arena settings");
        this.traits=Objects.requireNonNull(traits);this.traitRandom=Objects.requireNonNull(traitRandom);
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.grid = Objects.requireNonNull(grid);
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
        if (coinUnits < Gold.units(summonCost())) return Result.INSUFFICIENT_COINS;
        Cell cell = grid.placementOrder(roll.type().role()).stream().filter(c -> defenders.values().stream().noneMatch(d -> d.cell().equals(c))).findFirst().orElse(null);
        if (cell == null) return Result.FULL;
        Rarity grade=summonRarity(roll.rarity());
        Defender duplicate=defenders.values().stream().filter(d->d.type()==roll.type() && d.rarity()==grade).findFirst().orElse(null);
        if(duplicate!=null) {
            duplicate.merge(roll.rarity().salePrice().orElse(0));
            Defender match;
            while((match=matchingOther(duplicate))!=null) {
                duplicate.absorb(match);defenders.remove(match.entityId());mergedEntities.add(match.entityId());
                if(match.entityId().equals(selected))selected=duplicate.entityId();
            }
            lastSummoned=duplicate;
        }
        else {
            // Spawn before committing currency/occupancy: an adapter failure cannot consume a purchase.
            UUID entity = Objects.requireNonNull(spawner.spawn(roll.type(), grade, cell));
            if (hasEntity(entity)) throw new IllegalArgumentException("Duplicate entity UUID");
            lastSummoned=new Defender(entity, owner, id, roll.type(), grade, cell,
                    traits.value(TraitCatalog.Family.ENHANCEMENT)/100.0,roll.rarity().salePrice().orElse(0));
            defenders.put(entity, lastSummoned);
        }
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
        return traits.damageMultiplier(role,boss)+Math.min(traits.value(TraitCatalog.Family.SPENDING_DAMAGE),spentGold/1000)/100.0;
    }
    public UnitType summonType(UnitType original,Rarity rawGrade) {
        int chance=traits.value(TraitCatalog.Family.DUPLICATE_ODDS);
        if(chance==0)return original;
        Rarity grade=summonRarity(rawGrade);
        if(grade.ordinal()<Rarity.LEGENDARY.ordinal())return original;
        var owned=EnumSet.noneOf(UnitType.class);
        int highest=-1;
        for(Defender d:defenders.values())if(d.rarity()==grade) {
            if(d.enhancement()>highest){highest=d.enhancement();owned.clear();}
            if(d.enhancement()==highest)owned.add(d.type());
        }
        if(owned.isEmpty() || traitRandom.nextInt(100)>=chance)return original;
        return owned.stream().skip(traitRandom.nextInt(owned.size())).findFirst().orElseThrow();
    }
    private Defender matchingOther(Defender unit) {
        return defenders.values().stream().filter(d->d!=unit && d.type()==unit.type() && d.rarity()==unit.rarity()).findFirst().orElse(null);
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
        credit(defender.saleValue());
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
        long income = sold.stream().map(defenders::get).mapToLong(Defender::saleValue).reduce(0,Math::addExact);
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
