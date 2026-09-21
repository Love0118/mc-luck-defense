package dev.moma.core;

import java.util.*;
import java.util.function.Function;

/** Time-based waves overlap: surviving enemies remain on the circuit across rounds. */
public final class Campaign implements java.io.Serializable {
    private static final long serialVersionUID=1L;
    private final CampaignRules rules;
    private final boolean endless;
    private Wave wave;
    private long elapsed = -1;
    private int round;
    private int spawnedInRound;
    private int completedRounds;
    public Campaign(CampaignRules rules) { this(rules,false); }
    public Campaign(CampaignRules rules,boolean endless) { this.rules=rules;this.endless=endless; }
    public long elapsed() { return elapsed; }
    public int round() { return round; }
    /** Rounds whose final tick was survived; victory also completes round 100. */
    public int completedRounds() { return completedRounds; }
    public Wave wave() { return wave; }
    public boolean cleanup() { return !endless && elapsed >= rules.preparationTicks() + (long)rules.roundTicks() * CampaignRules.ROUNDS; }
    public int secondsRemaining() {
        long end = round == 0 ? rules.preparationTicks() : cleanup() ? rules.maximumTicks() : rules.preparationTicks() + (long)round * rules.roundTicks();
        return (int)Math.max(0, (end - elapsed + 19) / 20);
    }
    public void beforeCombat(Arena arena, Function<EnemySpawn, UUID> spawn) {
        if (arena.ended()) return;
        elapsed++;
        if (elapsed < rules.preparationTicks() || cleanup()) return;
        int current = (int)Math.min(endless?Integer.MAX_VALUE:CampaignRules.ROUNDS, (elapsed - rules.preparationTicks()) / rules.roundTicks() + 1);
        if (current != round) { round = current; arena.reachedRound(round); spawnedInRound = 0;wave=WaveSchedule.create(round,rules); }
        int offset = (int)((elapsed - rules.preparationTicks()) % rules.roundTicks());
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
        if (!endless && round == CampaignRules.ROUNDS && spawnedInRound == wave().entries().size() && arena.enemyCount() == 0)
            arena.finish(Arena.Outcome.VICTORY);
        else if (!endless && elapsed >= rules.maximumTicks()) arena.finish(Arena.Outcome.TIME_LIMIT);
        completedRounds = arena.outcome() == Arena.Outcome.VICTORY ? CampaignRules.ROUNDS
                : (int)Math.min(endless?Integer.MAX_VALUE:CampaignRules.ROUNDS, Math.max(0, (elapsed + 1 - rules.preparationTicks()) / rules.roundTicks()));
    }
}
