package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** CLI: runs seed health-scale output strategy primordial-cap health-curve boss-health-scale. */
public final class SimulatorMain {
    private SimulatorMain() {}
    public static void main(String[] args) throws Exception {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 100_000;
        CampaignRules rules = CampaignRules.standard();
        if (args.length > 2) rules = rules.withHealthScale(Double.parseDouble(args[2]));
        Path output = Path.of(args.length > 3 ? args[3] : "target/simulation");
        AutoPlayer.Strategy strategy = args.length > 4 ? AutoPlayer.Strategy.valueOf(args[4]) : AutoPlayer.Strategy.BALANCED;
        int primordialCap = args.length > 5 ? Integer.parseInt(args[5]) : Integer.MAX_VALUE;
        if (args.length > 6) rules = rules.withHealthCurve(HealthCurve.parse(args[6]));
        if (args.length > 7) rules = rules.withBossHealthScale(Double.parseDouble(args[7]));
        if (runs < 1 || runs > 100_000) throw new IllegalArgumentException("runs must be 1..100000");
        if (primordialCap < 0) throw new IllegalArgumentException("primordial-cap must not be negative");
        Files.createDirectories(output);
        final CampaignRules config = rules;
        int threads = Math.min(6, Runtime.getRuntime().availableProcessors());
        Simulation.Result[] results = new Simulation.Result[runs];
        AtomicInteger next = new AtomicInteger();
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<?>>();
            for (int worker = 0; worker < threads; worker++) futures.add(executor.submit(() -> {
                int i;
                while ((i = next.getAndIncrement()) < runs) {
                    results[i] = Simulation.run(seed + i, config, strategy, null, primordialCap);
                    if ((i + 1) % 1000 == 0) System.out.printf(Locale.ROOT, "completed %d/%d%n", i + 1, runs);
                }
            }));
            for (Future<?> future : futures) future.get();
        }
        int wins = (int) Arrays.stream(results).filter(r -> r.outcome() == Arena.Outcome.VICTORY).count();
        int lowPrimordialWins = (int) Arrays.stream(results).filter(r -> r.outcome() == Arena.Outcome.VICTORY && r.primordial() < 2).count();
        int targetRuns = (int) Arrays.stream(results).filter(r -> r.primordial() == 2 && r.mythic() >= 4).count();
        int targetWins = (int) Arrays.stream(results).filter(r -> r.outcome() == Arena.Outcome.VICTORY && r.primordial() == 2 && r.mythic() >= 4).count();
        double[] ci = wilson(wins, runs);
        double meanRounds = Arrays.stream(results).mapToInt(Simulation.Result::round).average().orElse(0);
        double meanSummons = Arrays.stream(results).mapToInt(Simulation.Result::summons).average().orElse(0);
        var checkpoints = new ArrayList<String>();
        for (int round : new int[]{30, 50, 70, 90}) {
            int survivors = (int) Arrays.stream(results).filter(r -> r.completedRounds() >= round).count();
            double[] bounds = wilson(survivors, runs);
            checkpoints.add(String.format(Locale.ROOT, "{\"round\":%d,\"survivors\":%d,\"rate\":%.8f,\"ci95Low\":%.8f,\"ci95High\":%.8f}", round, survivors, survivors / (double) runs, bounds[0], bounds[1]));
        }
        String summary = String.format(Locale.ROOT,
                "{\"runs\":%d,\"seedStart\":%d,\"healthScale\":%.8f,\"gridSize\":%d,\"primordialCap\":%d,\"strategy\":\"%s\",\"wins\":%d,\"winsBelowTwoPrimordials\":%d,\"twoPrimordialManyMythicRuns\":%d,\"twoPrimordialManyMythicWins\":%d,\"clearRate\":%.8f,\"ci95Low\":%.8f,\"ci95High\":%.8f,\"meanRound\":%.3f,\"meanSummons\":%.3f,\"seconds\":%.2f}",
                runs, seed, rules.healthScale(), rules.gridSize(), primordialCap, strategy, wins, lowPrimordialWins, targetRuns, targetWins, wins / (double) runs, ci[0], ci[1], meanRounds, meanSummons, (System.nanoTime() - start) / 1e9);
        summary = summary.substring(0, summary.length() - 1) + ",\"randomAlgorithm\":\"" + HashRandom.ALGORITHM + "\",\"healthCurve\":\"" + rules.healthCurve().specification() + "\",\"bossHealthScale\":" + rules.bossHealthScale() + ",\"checkpoints\":[" + String.join(",", checkpoints) + "]}";
        Files.writeString(output.resolve("summary.json"), summary + "\n", StandardCharsets.UTF_8);
        var lines = new ArrayList<String>();
        for (Simulation.Result r : results) lines.add(String.format(Locale.ROOT,
                "{\"seed\":%d,\"outcome\":\"%s\",\"round\":%d,\"completedRounds\":%d,\"ticks\":%d,\"summons\":%d,\"sales\":%d,\"moves\":%d,\"earned\":%d,\"coins\":%d,\"primordial\":%d,\"mythic\":%d,\"damage\":%s,\"deployedTicks\":%s}",
                r.seed(), r.outcome(), r.round(), r.completedRounds(), r.ticks(), r.summons(), r.sales(), r.moves(), r.earned(), r.coins(), r.primordial(), r.mythic(), Arrays.toString(r.damage()), Arrays.toString(r.deployedTicks())));
        Files.write(output.resolve("runs.jsonl"), lines, StandardCharsets.UTF_8);
        long replaySeed = Arrays.stream(results).filter(r -> r.outcome() == Arena.Outcome.VICTORY && r.primordial() == 2 && r.mythic() >= 4)
                .mapToLong(Simulation.Result::seed).findFirst().orElseGet(() -> Arrays.stream(results).filter(r -> r.outcome() == Arena.Outcome.VICTORY).mapToLong(Simulation.Result::seed).findFirst().orElse(seed));
        var trace = new ArrayList<String>();
        Simulation.run(replaySeed, rules, strategy, s -> trace.add(frameJson(s)), primordialCap);
        Files.write(output.resolve("trace-" + replaySeed + ".jsonl"), trace, StandardCharsets.UTF_8);
        try (var stream = SimulatorMain.class.getResourceAsStream("/replay-template.html")) {
            if (stream == null) throw new IllegalStateException("Missing replay template");
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__FRAMES__", "[" + String.join(",", trace) + "]")
                    .replace("__SUMMARY__", summary).replace("__SEED__", Long.toString(replaySeed));
            Files.writeString(output.resolve("replay.html"), html, StandardCharsets.UTF_8);
        }
        System.out.println(summary);
    }
    private static String frameJson(Simulation.Snapshot s) {
        String units = String.join(",", s.units().stream().map(u -> String.format(Locale.ROOT,
                "{\"type\":\"%s\",\"rarity\":\"%s\",\"role\":\"%s\",\"column\":%d,\"row\":%d}", u.type(), u.rarity(), u.role(), u.column(), u.row())).toList());
        String mobs = String.join(",", s.mobs().stream().map(e -> String.format(Locale.ROOT,
                "{\"type\":\"%s\",\"x\":%.4f,\"z\":%.4f,\"health\":%.2f,\"boss\":%s}", e.type(), e.x(), e.z(), e.health(), e.boss())).toList());
        return String.format(Locale.ROOT,
                "{\"tick\":%d,\"round\":%d,\"coins\":%d,\"enemies\":%d,\"defenders\":%d,\"summons\":%d,\"sales\":%d,\"moves\":%d,\"earned\":%d,\"units\":[%s],\"mobs\":[%s]}",
                s.tick(), s.round(), s.coins(), s.enemies(), s.defenders(), s.summons(), s.sales(), s.moves(), s.earned(), units, mobs);
    }
    public static double[] wilson(int wins, int count) {
        double p = wins / (double) count, z2 = 1.96 * 1.96;
        double center = (p + z2 / (2 * count)) / (1 + z2 / count);
        double margin = 1.96 * Math.sqrt(p * (1 - p) / count + z2 / (4.0 * count * count)) / (1 + z2 / count);
        return new double[]{Math.max(0, center - margin), Math.min(1, center + margin)};
    }
}
