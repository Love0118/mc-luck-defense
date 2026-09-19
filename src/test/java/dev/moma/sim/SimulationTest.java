package dev.moma.sim;

import dev.moma.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    @Test void seededRunsAreReproducibleAndHaveLegalIncomeAndActions() {
        var rules = CampaignRules.standard();
        var trace = new ArrayList<Simulation.Snapshot>();
        var first = Simulation.run(123, rules, AutoPlayer.Strategy.BALANCED, trace::add);
        var second = Simulation.run(123, rules, AutoPlayer.Strategy.BALANCED, null);
        assertEquals(first.outcome(), second.outcome()); assertEquals(first.summons(), second.summons());
        assertEquals(first.earned(), second.earned()); assertEquals(first.round(), second.round());
        assertArrayEquals(first.damage(), second.damage());
        assertTrue(first.summons() > 0); assertTrue(first.moves() > 0); assertTrue(first.sales() > 0);
        assertTrue(trace.stream().allMatch(s -> s.coins() >= 0 && s.enemies() <= rules.enemyLimit() && s.defenders() <= 25));
        for (int i = 1; i < trace.size(); i++) {
            assertTrue(trace.get(i).summons() >= trace.get(i - 1).summons());
            assertTrue(trace.get(i).earned() >= trace.get(i - 1).earned());
        }
    }
    @Test void noMovementPolicyAndWilsonBoundsBehaveAsDeclared() {
        var result = Simulation.run(456, CampaignRules.standard(), AutoPlayer.Strategy.AUTO_PLACE, null);
        assertEquals(0, result.moves());
        double[] interval = SimulatorMain.wilson(100, 10_000);
        assertTrue(interval[0] < 0.01 && interval[1] > 0.01);
        assertTrue(SimulatorMain.wilson(0, 100)[1] > 0);
    }
    @Test void twoPrimordialSevenMythicRosterClearsButAnotherMythicCannotReplaceTheSecondPrimordial() {
        var result = Simulation.run(100_124, CampaignRules.standard(), AutoPlayer.Strategy.BALANCED, null);
        assertEquals(Arena.Outcome.VICTORY, result.outcome());
        assertEquals(100, result.round());
        assertEquals(2, result.primordial()); assertEquals(7, result.mythic());
        var replacement = Simulation.run(100_124, CampaignRules.standard(), AutoPlayer.Strategy.BALANCED, null, 1);
        assertNotEquals(Arena.Outcome.VICTORY, replacement.outcome());
        assertEquals(1, replacement.primordial()); assertEquals(8, replacement.mythic());
        assertEquals(result.summons(), replacement.summons());
    }
    @Test void unlimitedStressSettingMatchesOrdinaryPlay() {
        var regular = Simulation.run(789, CampaignRules.standard(), AutoPlayer.Strategy.BALANCED, null);
        var unlimited = Simulation.run(789, CampaignRules.standard(), AutoPlayer.Strategy.BALANCED, null, Integer.MAX_VALUE);
        assertEquals(regular.outcome(), unlimited.outcome());
        assertEquals(regular.summons(), unlimited.summons());
        assertEquals(regular.primordial(), unlimited.primordial());
        assertArrayEquals(regular.damage(), unlimited.damage());
    }
    @Test void previouslyObservedOnePrimordialSevenMythicExceptionNowFailsCombat() {
        var result = Simulation.run(2_110_951, CampaignRules.standard(), AutoPlayer.Strategy.BALANCED, null);
        assertEquals(1, result.primordial()); assertEquals(7, result.mythic());
        assertNotEquals(Arena.Outcome.VICTORY, result.outcome());
    }
}
