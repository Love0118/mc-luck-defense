package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;

/** Fixed independent diagnostic sample, not a substitute for rejection-sampling correctness. */
public final class RandomAuditMain {
    private RandomAuditMain() {}
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/random-audit.json" : args[0]);
        int streams = 2000, drawsPerStream = 1200, draws = streams * drawsPerStream;
        int[] rarity = new int[9], species = new int[24]; int[][] joint = new int[9][24];
        for (int seed = 0; seed < streams; seed++) {
            var random = new HashRandom(9_000_000L + seed);
            for (int i = 0; i < drawsPerStream; i++) {
                SummonRoll roll = SummonRoll.draw(random);
                rarity[roll.rarity().ordinal()]++; species[roll.type().ordinal()]++;
                joint[roll.rarity().ordinal()][roll.type().ordinal()]++;
            }
        }
        double speciesChi = 0, rarityChi = 0, independenceChi = 0;
        for (int n : species) speciesChi += Math.pow(n - draws / 24.0, 2) / (draws / 24.0);
        for (Rarity tier : Rarity.values()) {
            int r = tier.ordinal(); double expected = (double) draws * tier.weight() / Rarity.TOTAL_WEIGHT;
            rarityChi += Math.pow(rarity[r] - expected, 2) / expected;
            for (int s = 0; s < 24; s++) {
                expected = (double) rarity[r] * species[s] / draws;
                independenceChi += Math.pow(joint[r][s] - expected, 2) / expected;
            }
        }
        var tiers = new ArrayList<String>();
        for (Rarity tier : Rarity.values()) tiers.add(String.format(Locale.ROOT,
                "{\"rarity\":\"%s\",\"count\":%d,\"expectedRate\":%.8f,\"observedRate\":%.8f}",
                tier, rarity[tier.ordinal()], tier.weight() / 100_000.0, rarity[tier.ordinal()] / (double) draws));
        String json = String.format(Locale.ROOT,
                "{\"randomAlgorithm\":\"%s\",\"seedStart\":9000000,\"streams\":%d,\"drawsPerStream\":%d,\"draws\":%d,\"speciesCounts\":%s,\"rarities\":[%s],\"speciesChiSquareDf23\":%.6f,\"rarityChiSquareDf8\":%.6f,\"independenceChiSquareDf184\":%.6f}\n",
                HashRandom.ALGORITHM, streams, drawsPerStream, draws, Arrays.toString(species), String.join(",", tiers), speciesChi, rarityChi, independenceChi);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json);
        System.out.println(json);
    }
}
