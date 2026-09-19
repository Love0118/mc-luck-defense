package dev.moma.core;

import java.util.*;
import java.util.function.Function;

/** Time-based waves overlap: surviving enemies remain on the circuit across rounds. */
public final class Campaign {
    private final CampaignRules rules;
    private final List<Wave> waves;
    private int elapsed = -1;
    private int round;
    private int spawnedInRound;
    private int completedRounds;
    public Campaign(CampaignRules rules) { this.rules = rules; waves = WaveSchedule.create(rules); }
    public int elapsed() { return elapsed; }
    public int round() { return round; }
    /** Rounds whose final tick was survived; victory also completes round 100. */
    public int completedRounds() { return completedRounds; }
    public Wave wave() { return round == 0 ? null : waves.get(round - 1); }
    public boolean cleanup() { return elapsed >= rules.preparationTicks() + rules.roundTicks() * CampaignRules.ROUNDS; }
    public int secondsRemaining() {
        int end = round == 0 ? rules.preparationTicks() : cleanup() ? rules.maximumTicks() : rules.preparationTicks() + round * rules.roundTicks();
        return Math.max(0, (end - elapsed + 19) / 20);
    }
    public void beforeCombat(Arena arena, Function<EnemySpawn, UUID> spawn) {
        if (arena.ended()) return;
        elapsed++;
        if (elapsed < rules.preparationTicks() || cleanup()) return;
        int current = Math.min(CampaignRules.ROUNDS, (elapsed - rules.preparationTicks()) / rules.roundTicks() + 1);
        if (current != round) { round = current; spawnedInRound = 0; }
        int offset = (elapsed - rules.preparationTicks()) % rules.roundTicks();
        List<Wave.Entry> entries = wave().entries();
        while (spawnedInRound < entries.size() && entries.get(spawnedInRound).offsetTick() <= offset && !arena.ended()) {
            EnemySpawn spec = entries.get(spawnedInRound).enemy();
            UUID id = Objects.requireNonNull(spawn.apply(spec));
            arena.addEnemy(spec.create(id, arena.id()));
            spawnedInRound++;
        }
    }
    public void afterCombat(Arena arena) {
        if (arena.ended()) return;
        if (round == CampaignRules.ROUNDS && spawnedInRound == wave().entries().size() && arena.enemyCount() == 0)
            arena.finish(Arena.Outcome.VICTORY);
        else if (elapsed >= rules.maximumTicks()) arena.finish(Arena.Outcome.TIME_LIMIT);
        completedRounds = arena.outcome() == Arena.Outcome.VICTORY ? CampaignRules.ROUNDS
                : Math.min(CampaignRules.ROUNDS, Math.max(0, (elapsed + 1 - rules.preparationTicks()) / rules.roundTicks()));
    }
}
