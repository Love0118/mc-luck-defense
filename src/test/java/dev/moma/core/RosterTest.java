package dev.moma.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterTest {
    @Test void exhaustiveProbabilityBucketsMatchPublishedTable() {
        int[] expected = {50001, 33100, 10200, 5100, 800, 500, 200, 80, 19};
        int[] counts = new int[9];
        for (int roll = 0; roll < 100_000; roll++) counts[Rarity.fromRoll(roll).ordinal()]++;
        assertArrayEquals(expected, counts);
        assertThrows(IllegalArgumentException.class, () -> Rarity.fromRoll(-1));
        assertThrows(IllegalArgumentException.class, () -> Rarity.fromRoll(100_000));
        assertNotEquals(Rarity.NARRATIVE, Rarity.EPIC);
    }
    @Test void distinctFactionsAndSixEvenRoles() {
        assertEquals(24, UnitType.values().length);
        Set<String> enemyNames = new HashSet<>();
        for (EnemyType type : EnemyType.values()) enemyNames.add(type.name());
        for (AttackRole role : AttackRole.values()) assertEquals(4, Arrays.stream(UnitType.values()).filter(u -> u.role() == role).count());
        assertTrue(enemyNames.contains(UnitType.WARDEN.name()));
        assertNotEquals(Faction.ENEMY,Faction.DEFENDER); // Appearance is no longer a faction identifier.
    }
    @Test void independentDrawsCanProduceEverySpeciesAtEveryRarity() {
        int boundary = 0;
        for (Rarity rarity : Rarity.values()) {
            if(rarity.weight()==0)continue;
            for (UnitType type : UnitType.values()) {
                int roll = boundary;
                var random = new java.util.random.RandomGenerator() {
                    int calls;
                    @Override public long nextLong() { throw new AssertionError("Must use separate bounded draws"); }
                    @Override public int nextInt(int bound) {
                        assertEquals(calls == 0 ? 100_000 : 24, bound);
                        return calls++ == 0 ? roll : type.ordinal();
                    }
                };
                assertEquals(new SummonRoll(type, rarity), SummonRoll.draw(random));
            }
            boundary += rarity.weight();
        }
    }
    @Test void saleTableAndExpectedRecovery() {
        int[] expected = {1, 3, 5, 24, 35, 60, 400, 1000,1500,30000,630000};
        double mean = 0;
        for (Rarity rarity : Rarity.values()) {
            if (rarity.ordinal() < expected.length) assertEquals(expected[rarity.ordinal()], rarity.salePrice().orElseThrow());
            else assertTrue(rarity.salePrice().isEmpty());
            mean += rarity.weight() / 100_000.0 * rarity.salePrice().orElse(0);
        }
        assertEquals(5.69201, mean, 1e-9);
        assertTrue(mean < Arena.SUMMON_COST);
    }
    @ParameterizedTest @EnumSource(UnitType.class)
    void higherRarityImprovesEverySpeciesWithoutMakingMeleeLongRange(UnitType type) {
        CombatProfile previous = null;
        for (Rarity rarity : Rarity.values()) {
            CombatProfile current = type.profile().at(rarity);
            if (previous != null) {
                assertTrue(current.damage() > previous.damage());
                assertTrue(current.intervalTicks() <= previous.intervalTicks());
                assertTrue(current.range() >= previous.range());
            }
            if (type.role().melee()) assertTrue(current.range() < 7);
            previous = current;
        }
    }
    @ParameterizedTest @EnumSource(UnitType.class)
    void upperTiersHaveMeaningfulDpsSeparationForEverySpecies(UnitType type) {
        CombatProfile legend = type.profile().at(Rarity.LEGENDARY);
        CombatProfile epic = type.profile().at(Rarity.EPIC);
        CombatProfile mythic = type.profile().at(Rarity.MYTHIC);
        CombatProfile primordial = type.profile().at(Rarity.PRIMORDIAL);
        assertTrue(dps(epic) >= dps(legend) * 2.5);
        assertTrue(dps(mythic) >= dps(epic) * 2.25);
        assertTrue(dps(primordial) >= dps(mythic) * 20);
    }
    @ParameterizedTest @EnumSource(UnitType.class)
    void legendaryBeatsCommonNineteenEvenWithMaximumEnhancementTrait(UnitType type) {
        var d=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,Rarity.COMMON,new Cell(0,0),.30,1);
        for(int i=0;i<19;i++)d.merge();
        assertTrue(dps(type.profile().at(Rarity.LEGENDARY))>dps(d.profile()));
        var mythic=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,Rarity.MYTHIC,new Cell(0,0),.30,1000);
        for(int i=0;i<19;i++)mythic.merge();
        assertTrue(dps(type.profile().at(Rarity.PRIMORDIAL))>dps(mythic.profile()));
    }
    @ParameterizedTest @EnumSource(UnitType.class)
    void everyAdjacentGradeBeatsLowerNineteenAndPromotionKeepsCanonicalStats(UnitType type) {
        double maximumBonus=TraitCatalog.ALL.stream().filter(t->t.family()==TraitCatalog.Family.ENHANCEMENT)
                .mapToInt(TraitCatalog.Entry::value).max().orElseThrow()/100.0;
        Rarity[] grades=Rarity.values();
        for(int i=0;i<grades.length-1;i++)for(double bonus:new double[]{0,maximumBonus}) {
            var lower=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,grades[i],new Cell(0,0),bonus,0);
            for(int n=0;n<19;n++)lower.merge();
            var higher=type.profile().at(grades[i+1]);
            assertTrue(higher.damage()>lower.profile().damage(),type+" "+grades[i]);
            assertTrue(dps(higher)>dps(lower.profile()),type+" "+grades[i]);
            lower.merge();
            assertEquals(grades[i+1],lower.rarity());assertEquals(0,lower.enhancement());
            assertEquals(higher,lower.profile(),"Promotion must not carry hidden damage");
        }
        var chained=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,Rarity.COMMON,new Cell(0,0),maximumBonus,0);
        for(int i=1;i<grades.length;i++) {
            for(int n=0;n<20;n++)chained.merge();
            assertEquals(type.profile().at(grades[i]),chained.profile());
        }
    }
    @ParameterizedTest @EnumSource(UnitType.class)
    void primordialThroughMiracleKeepTheirOriginalCombatProfiles(UnitType type) {
        double[] damage={4800,480000,48000000};int[] abilities={4,4,5};
        Rarity[] grades={Rarity.PRIMORDIAL,Rarity.TRUE_PRIMORDIAL,Rarity.MIRACLE};
        for(int i=0;i<grades.length;i++) {
            var original=type.profile();var profile=original.at(grades[i]);
            assertEquals(original.damage()*damage[i],profile.damage());
            assertEquals(Math.max(2,(int)Math.ceil(original.intervalTicks()/2.3)),profile.intervalTicks());
            assertEquals(original.range()*1.4,profile.range());
            assertEquals(original.areaRadius(),profile.areaRadius());assertEquals(original.targets(),profile.targets());
            assertEquals(abilities[i],grades[i].abilityLevel());
        }
    }
    private double dps(CombatProfile profile) { return profile.damage() * 20 / profile.intervalTicks(); }
}
