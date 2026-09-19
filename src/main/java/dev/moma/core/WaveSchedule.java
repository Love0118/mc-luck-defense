package dev.moma.core;

import java.util.*;

public final class WaveSchedule {
    private WaveSchedule() {}
    public static List<Wave> create(CampaignRules rules) {
        var waves = new ArrayList<Wave>();
        for (int round = 1; round <= CampaignRules.ROUNDS; round++) waves.add(create(round, rules));
        return List.copyOf(waves);
    }
    private static Wave create(int round, CampaignRules rules) {
        int pattern = (round - 1) % 5;
        int count = 12 + (round - 1) / 10 * 2;
        if (pattern == 2) count += 8;
        if (pattern == 3) count -= 3;
        double base = 24 * Math.pow(1.055, round - 1) * rules.healthScale();
        var entries = new ArrayList<Wave.Entry>();
        for (int i = 0; i < count; i++) {
            EnemyType type = switch (pattern) {
                case 0 -> i % 3 == 0 ? EnemyType.HUSK : EnemyType.ZOMBIE;
                case 1 -> i % 4 == 0 ? EnemyType.DROWNED : EnemyType.SPIDER;
                case 2 -> i % 3 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.SLIME;
                case 3 -> i % 3 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.HUSK;
                default -> EnemyType.values()[i % EnemyType.values().length];
            };
            double health = base * switch (type) {
                case ZOMBIE -> 1;
                case HUSK -> 1.65;
                case DROWNED -> 1.2;
                case SPIDER -> 0.65;
                case SLIME -> 0.55;
                case MAGMA_CUBE -> 1.35;
            };
            double speed = switch (type) {
                case ZOMBIE -> 2.0;
                case HUSK -> 1.5;
                case DROWNED -> 1.8;
                case SPIDER -> 3.5;
                case SLIME -> 2.5;
                case MAGMA_CUBE -> 2.1;
            };
            int spawnWindow = rules.roundTicks() * 3 / 4;
            entries.add(new Wave.Entry(i * spawnWindow / count, new EnemySpawn(type, health, speed, 6, false)));
        }
        if (round % 10 == 0) entries.add(new Wave.Entry(rules.roundTicks() / 2,
                new EnemySpawn(round % 20 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.HUSK, base * (15 + round / 5.0), 1.2, 30, true)));
        entries.sort(Comparator.comparingInt(Wave.Entry::offsetTick));
        String name = switch (pattern) { case 0 -> "보병"; case 1 -> "돌격"; case 2 -> "군집"; case 3 -> "중장갑"; default -> "혼성"; };
        return new Wave(round, round % 10 == 0 ? name + " + 보스" : name, entries);
    }
}
