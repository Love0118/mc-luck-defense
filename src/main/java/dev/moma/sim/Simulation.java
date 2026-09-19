package dev.moma.sim;

import dev.moma.core.*;
import java.util.*;
import java.util.function.Consumer;

public final class Simulation {
    public record UnitFrame(String type, String rarity, String role, int column, int row) {}
    public record EnemyFrame(String type, double x, double z, double health, boolean boss) {}
    public record Snapshot(int tick, int round, long coins, int enemies, int defenders, int summons, int sales, int moves, long earned,
                           List<UnitFrame> units, List<EnemyFrame> mobs) {}
    public record Result(long seed, Arena.Outcome outcome, int round, int completedRounds, int ticks, int summons, int sales, int moves,
                         long earned, long coins, int primordial, int mythic, double[] damage, long[] deployedTicks) {}
    private Simulation() {}
    public static Result run(long seed, CampaignRules rules, AutoPlayer.Strategy strategy, Consumer<Snapshot> trace) {
        return run(seed, rules, strategy, trace, Integer.MAX_VALUE);
    }
    public static Result run(long seed, CampaignRules rules, AutoPlayer.Strategy strategy, Consumer<Snapshot> trace, int primordialCap) {
        UUID owner = new UUID(0, 1);
        long[] sequence = {1};
        var ids = (java.util.function.Supplier<UUID>) () -> new UUID(seed, ++sequence[0]);
        Arena arena = new Arena("simulation", owner, new Grid(rules.gridSize()), rules.startingCoins(), rules.enemyLimit());
        Campaign campaign = new Campaign(rules);
        AutoPlayer bot = new AutoPlayer(seed, strategy, arena.grid(), ids, primordialCap);
        CombatEngine combat = new CombatEngine();
        double[] damage = new double[6]; long[] exposure = new long[6];
        CombatEngine.HitSink sink = (d, e, amount) -> damage[d.type().role().ordinal()] += amount;
        int tick = 0;
        for (; tick <= rules.maximumTicks() && !arena.ended(); tick++) {
            campaign.beforeCombat(arena, spawn -> ids.get());
            bot.act(arena, tick);
            combat.tick(arena, tick, sink);
            arena.collectDeadEnemies();
            campaign.afterCombat(arena);
            if (tick % 20 == 0 || arena.ended()) {
                for (Defender defender : arena.defenders()) exposure[defender.type().role().ordinal()] += 20;
                if (trace != null) trace.accept(new Snapshot(tick, campaign.round(), arena.coins(), arena.enemyCount(), arena.defenderCount(), bot.summons(), bot.sales(), bot.moves(), arena.earnedCoins(),
                        arena.defenders().stream().map(d -> new UnitFrame(d.type().name(), d.rarity().name(), d.type().role().name(), d.cell().column(), d.cell().row())).toList(),
                        arena.enemies().stream().map(e -> new EnemyFrame(e.type().name(), e.position(arena.grid().route()).x(), e.position(arena.grid().route()).z(), e.health(), e.boss())).toList()));
            }
        }
        if (!arena.ended()) throw new IllegalStateException("Campaign failed to terminate");
        int[] rarities = bot.rarities();
        return new Result(seed, arena.outcome(), campaign.round(), campaign.completedRounds(), tick, bot.summons(), bot.sales(), bot.moves(), arena.earnedCoins(), arena.coins(),
                rarities[Rarity.PRIMORDIAL.ordinal()], rarities[Rarity.MYTHIC.ordinal()], damage, exposure);
    }
}
