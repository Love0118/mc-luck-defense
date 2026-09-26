package dev.moma.core;

import java.util.*;
import java.util.function.Predicate;
import dev.moma.core.TraitCatalog.*;

/** Immutable, validated session snapshot. Unlock checks belong to the account boundary. */
public final class TraitLoadout implements java.io.Serializable {
    private static final long serialVersionUID=1L;
    public static final TraitLoadout EMPTY=new TraitLoadout(List.of());
    private final List<Entry> entries;
    private final int[] values=new int[Family.values().length];
    private final AttackRole role;
    private final Rarity purchaseCeiling;
    private final Rarity openingTarget;
    public TraitLoadout(List<String> ids) {
        List<Entry> selected=new ArrayList<>();Set<Family> families=EnumSet.noneOf(Family.class);AttackRole selectedRole=null;Rarity ceiling=Rarity.LEGENDARY,target=null;
        Entry promotion=null;Map<Rarity,Entry> opening=new EnumMap<>(Rarity.class);
        for(String id:ids) {
            Entry e=TraitCatalog.find(id);
            if(e==null)throw new IllegalArgumentException("없는 특성입니다.");
            if(e.family()==Family.FIRST_PURCHASE) {
                if(promotion==null || e.purchaseCeiling().ordinal()>promotion.purchaseCeiling().ordinal()
                        || e.purchaseCeiling()==promotion.purchaseCeiling() && e.value()>promotion.value())promotion=e;
            } else if(e.family()==Family.OPENING_ODDS) {
                opening.merge(e.openingTarget(),e,(old,next)->old.value()>=next.value()?old:next);
            } else {
                if(!families.add(e.family()))throw new IllegalArgumentException("같은 계열은 하나만 장착할 수 있습니다.");
                selected.add(e);values[e.family().ordinal()]=e.value();if(e.role()!=null)selectedRole=e.role();
            }
        }
        if(selected.size()>4)throw new IllegalArgumentException("특성은 최대 4개입니다.");
        if(promotion!=null){selected.add(promotion);ceiling=promotion.purchaseCeiling();values[Family.FIRST_PURCHASE.ordinal()]=promotion.value();}
        for(Entry e:opening.values()){selected.add(e);target=e.openingTarget();values[Family.OPENING_ODDS.ordinal()]=Math.max(values[Family.OPENING_ODDS.ordinal()],e.value());}
        entries=List.copyOf(selected);role=selectedRole;purchaseCeiling=ceiling;openingTarget=target;
    }
    public static TraitLoadout unlocked(List<String> ids,long highestRound,Predicate<Entry> unlocked) {
        TraitLoadout loadout=new TraitLoadout(ids);
        if(loadout.entries().size()>TraitCatalog.slots(highestRound))throw new IllegalArgumentException("아직 열리지 않은 특성 슬롯입니다.");
        for(Entry e:loadout.entries)if(!unlocked.test(e))throw new IllegalArgumentException("아직 해금하지 않은 특성입니다.");
        return loadout;
    }
    public List<Entry> entries() { return entries.stream().filter(e->!e.passive()).toList(); }
    private Object readResolve() {
        // Retain the old enum constant so pre-income snapshots can be deserialized and migrated by ID.
        return entries.stream().anyMatch(e->e.family()==Family.SPENDING_DAMAGE)?new TraitLoadout(allIds()):this;
    }
    public List<Entry> passives() { return entries.stream().filter(Entry::passive).toList(); }
    public List<Entry> allEntries() { return entries; }
    public List<String> allIds() { return entries.stream().map(Entry::id).toList(); }
    public List<String> ids() { return entries().stream().map(Entry::id).toList(); }
    public int value(Family family) { return family.ordinal()<values.length?values[family.ordinal()]:0; }
    public Rarity openingTarget() { return openingTarget; }
    public int openingWeight(Rarity rarity) { return entries.stream().filter(e->e.family()==Family.OPENING_ODDS && e.openingTarget()==rarity).mapToInt(Entry::value).max().orElse(0); }
    public Rarity summonedRarity(Rarity original,long purchaseIndex) {
        return purchaseIndex<value(Family.FIRST_PURCHASE) && original.ordinal()<=Math.min(purchaseCeiling.ordinal(),Rarity.EPIC.ordinal())
                ? Rarity.values()[original.ordinal()+1]:original;
    }
    public double damageMultiplier(AttackRole attackRole,boolean boss) {
        return 1+(value(Family.DAMAGE)+value(boss?Family.BOSS_DAMAGE:Family.NORMAL_DAMAGE)
                +(role==attackRole?value(Family.ROLE_DAMAGE):0))/100.0;
    }
}
