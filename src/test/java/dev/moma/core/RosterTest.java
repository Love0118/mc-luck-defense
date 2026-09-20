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
        int[] expected = {3, 6, 9, 16, 30, 60, 150, 375};
        double mean = 0;
        for (Rarity rarity : Rarity.values()) {
            if (rarity.ordinal() < expected.length) assertEquals(expected[rarity.ordinal()], rarity.salePrice().orElseThrow());
            else assertTrue(rarity.salePrice().isEmpty());
            mean += rarity.weight() / 100_000.0 * rarity.salePrice().orElse(0);
        }
        assertEquals(6.36003, mean, 1e-9);
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
        assertTrue(dps(epic) >= dps(legend) * 3);
        assertTrue(dps(mythic) >= dps(epic) * 5);
        assertTrue(dps(primordial) >= dps(mythic) * 20);
    }
    private double dps(CombatProfile profile) { return profile.damage() * 20 / profile.intervalTicks(); }
}
