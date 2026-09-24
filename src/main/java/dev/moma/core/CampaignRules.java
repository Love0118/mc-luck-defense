package dev.moma.core;

import java.io.*;
import java.util.Properties;

/** One checked-in ruleset consumed by both Paper and the standalone simulator. */
public record CampaignRules(int gridSize, long startingCoins, int enemyLimit, int preparationTicks,
                            int roundTicks, int cleanupTicks, double healthScale, HealthCurve healthCurve,
                            double bossHealthScale, double endlessHealthPower, double endlessPressureBend) implements java.io.Serializable {
    public static final int ROUNDS = 100;
    public CampaignRules {
        if (gridSize < 2 || gridSize > 15 || startingCoins < 0 || enemyLimit < 2 || preparationTicks < 0
                || roundTicks < 100 || cleanupTicks < 1 || !Double.isFinite(healthScale) || healthScale <= 0
                || healthCurve == null || !Double.isFinite(bossHealthScale) || bossHealthScale <= 0
                || !Double.isFinite(endlessHealthPower) || endlessHealthPower <= 2 || endlessHealthPower > 8
                || !Double.isFinite(endlessPressureBend) || endlessPressureBend < 0 || endlessPressureBend > 4)
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
                Double.parseDouble(p.getProperty("health-scale")), HealthCurve.parse(p.getProperty("health-curve")),
                Double.parseDouble(p.getProperty("boss-health-scale")), Double.parseDouble(p.getProperty("endless-health-power")),
                Double.parseDouble(p.getProperty("endless-pressure-bend")));
    }
    public CampaignRules withHealthScale(double scale) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, scale, healthCurve, bossHealthScale, endlessHealthPower, endlessPressureBend); }
    public CampaignRules withHealthCurve(HealthCurve curve) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, healthScale, curve, bossHealthScale, endlessHealthPower, endlessPressureBend); }
    public CampaignRules withBossHealthScale(double scale) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, healthScale, healthCurve, scale, endlessHealthPower, endlessPressureBend); }
    public CampaignRules withEndlessHealthPower(double power) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, healthScale, healthCurve, bossHealthScale, power, endlessPressureBend); }
    public CampaignRules withEndlessPressureBend(double bend) { return new CampaignRules(gridSize, startingCoins, enemyLimit, preparationTicks, roundTicks, cleanupTicks, healthScale, healthCurve, bossHealthScale, endlessHealthPower, bend); }
    public int maximumTicks() { return preparationTicks + ROUNDS * roundTicks + cleanupTicks; }
}
