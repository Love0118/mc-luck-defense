package dev.moma.core;

import java.io.*;
import java.util.Properties;

/** One checked-in ruleset consumed by both Paper and the standalone simulator. */
public record CampaignRules(int gridSize, long startingCoins, int enemyLimit, int preparationTicks,
                            int roundTicks, int cleanupTicks, double healthScale, double lateHealthScale) {
    public static final int ROUNDS = 100;
    public CampaignRules {
        if (gridSize < 2 || gridSize > 15 || startingCoins < 0 || enemyLimit < 2 || preparationTicks < 0
                || roundTicks < 100 || cleanupTicks < 1 || !Double.isFinite(healthScale) || healthScale <= 0
                || !Double.isFinite(lateHealthScale) || lateHealthScale < 1)
            throw new IllegalArgumentException("Invalid campaign rules");
    }
    public static CampaignRules standard() {
        Properties p = new Properties();
        try (InputStream stream = CampaignRules.class.getResourceAsStream("/campaign.properties")) {
            if (stream == null) throw new IllegalStateException("Missing campaign.properties");
            p.load(stream);
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
        return new CampaignRules(Integer.parseInt(p.getProperty("grid-size")), Long.parseLong(p.getProperty("starting-coins")),
                Integer.parseInt(p.getProperty("enemy-limit")), Integer.parseInt(p.getProperty("preparation-ticks")),
                Integer.parseInt(p.getProperty("round-ticks")), Integer.parseInt(p.getProperty("cleanup-ticks")),
                Double.parseDouble(p.getProperty("health-scale")), Double.parseDouble(p.getProperty("late-health-scale")));
    }
    public CampaignRules withHealthScale(double scale) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, scale, lateHealthScale); }
    public CampaignRules withLateHealthScale(double scale) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, healthScale, scale); }
    public int maximumTicks() { return preparationTicks + ROUNDS * roundTicks + cleanupTicks; }
}
