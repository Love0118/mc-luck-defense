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
        if(ids.size()>3)throw new IllegalArgumentException("특성은 최대 3개입니다.");
        List<Entry> selected=new ArrayList<>();Set<Family> families=EnumSet.noneOf(Family.class);AttackRole selectedRole=null;Rarity ceiling=Rarity.LEGENDARY,target=null;
        for(String id:ids) {
            Entry e=TraitCatalog.find(id);
            if(e==null)throw new IllegalArgumentException("없는 특성입니다.");
            if(!families.add(e.family()))throw new IllegalArgumentException("같은 계열은 하나만 장착할 수 있습니다.");
            selected.add(e);values[e.family().ordinal()]=e.value();if(e.role()!=null)selectedRole=e.role();
            if(e.family()==Family.FIRST_PURCHASE)ceiling=e.purchaseCeiling();
            if(e.family()==Family.OPENING_ODDS)target=e.openingTarget();
        }
        entries=List.copyOf(selected);role=selectedRole;purchaseCeiling=ceiling;openingTarget=target;
    }
    public static TraitLoadout unlocked(List<String> ids,long highestRound,Predicate<Entry> unlocked) {
        TraitLoadout loadout=new TraitLoadout(ids);
        if(ids.size()>TraitCatalog.slots(highestRound))throw new IllegalArgumentException("아직 열리지 않은 특성 슬롯입니다.");
        for(Entry e:loadout.entries)if(!unlocked.test(e))throw new IllegalArgumentException("아직 해금하지 않은 특성입니다.");
        return loadout;
    }
    public List<Entry> entries() { return entries; }
    public List<String> ids() { return entries.stream().map(Entry::id).toList(); }
    public int value(Family family) { return values[family.ordinal()]; }
    public Rarity openingTarget() { return openingTarget; }
    public Rarity summonedRarity(Rarity original,long purchaseIndex) {
        return purchaseIndex<value(Family.FIRST_PURCHASE) && original.ordinal()<=Math.min(purchaseCeiling.ordinal(),Rarity.EPIC.ordinal())
                ? Rarity.values()[original.ordinal()+1]:original;
    }
    public double damageMultiplier(AttackRole attackRole,boolean boss) {
        return 1+(value(Family.DAMAGE)+value(boss?Family.BOSS_DAMAGE:Family.NORMAL_DAMAGE)
                +(role==attackRole?value(Family.ROLE_DAMAGE):0))/100.0;
    }
}
