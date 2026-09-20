package dev.moma.core;

import java.util.*;

public final class WaveSchedule {
    // These six profiles define the existing difficulty budget, independently of appearance.
    private static final EnemyType[] BUDGET_TYPES = {EnemyType.ZOMBIE, EnemyType.HUSK, EnemyType.DROWNED, EnemyType.SPIDER, EnemyType.SLIME, EnemyType.MAGMA_CUBE};
    public static final double FINAL_BOSS_HEALTH_MULTIPLIER = 1.75;
    private static final double[] REWARDS = {.1,.1,.2,.5,1,3,6,10,15,30};
    public static double reward(int round) {
        if (round < 1) throw new IllegalArgumentException("Round must be positive");
        return round<=100?REWARDS[(round-1)/10]:30+((round-100)/10)*2L;
    }
    private WaveSchedule() {}
    public static List<Wave> create(CampaignRules rules) {
        var waves = new ArrayList<Wave>();
        for (int round = 1; round <= CampaignRules.ROUNDS; round++) waves.add(create(round, rules));
        return List.copyOf(waves);
    }
    public static Wave create(int round, CampaignRules rules) {
        int cycleRound=(round-1)%100+1;
        int pattern = (cycleRound - 1) % 5;
        int count = 12 + (cycleRound - 1) / 10 * 2;
        if (pattern == 2) count += 8;
        if (pattern == 3) count -= 3;
        int lastAnchor=rules.healthCurve().anchors().getLast().round();
        double growth=round<=lastAnchor?1:Math.pow(round/(double)lastAnchor,2);
        double base = rules.healthCurve().at(Math.min(round,lastAnchor)) * rules.healthScale()*growth;
        var entries = new ArrayList<Wave.Entry>();
        for (int i = 0; i < count; i++) {
            EnemyType type = switch (pattern) {
                case 0 -> i % 3 == 0 ? EnemyType.HUSK : EnemyType.ZOMBIE;
                case 1 -> i % 4 == 0 ? EnemyType.DROWNED : EnemyType.SPIDER;
                case 2 -> i % 3 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.SLIME;
                case 3 -> i % 3 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.HUSK;
                default -> BUDGET_TYPES[i % BUDGET_TYPES.length];
            };
            double health = base * switch (type) {
                case ZOMBIE -> 1;
                case HUSK -> 1.65;
                case DROWNED -> 1.2;
                case SPIDER -> 0.65;
                case SLIME -> 0.55;
                case MAGMA_CUBE -> 1.35;
                default -> throw new IllegalStateException("Unexpected budget profile");
            };
            double speed = switch (type) {
                case ZOMBIE -> 2.0;
                case HUSK -> 1.5;
                case DROWNED -> 1.8;
                case SPIDER -> 3.5;
                case SLIME -> 2.5;
                case MAGMA_CUBE -> 2.1;
                default -> throw new IllegalStateException("Unexpected budget profile");
            };
            int spawnWindow = rules.roundTicks() * 3 / 4;
            entries.add(new Wave.Entry(i * spawnWindow / count, new EnemySpawn(type, health, speed, reward(round), false)));
        }
        if (round % 10 == 0) entries.add(new Wave.Entry(rules.roundTicks() / 2,
                new EnemySpawn(round % 20 == 0 ? EnemyType.MAGMA_CUBE : EnemyType.HUSK,
                        base * (15 + cycleRound / 5.0) * rules.bossHealthScale() * (cycleRound == 100 ? FINAL_BOSS_HEALTH_MULTIPLIER : 1), 1.2, reward(round)*25, true)));
        entries.sort(Comparator.comparingInt(Wave.Entry::offsetTick));
        return themed(round, entries);
    }
    private static Wave themed(int round, List<Wave.Entry> budget) {
        WaveTheme theme=WaveTheme.at(round);
        var result=new ArrayList<Wave.Entry>();
        var regular=budget.stream().filter(e->!e.enemy().boss()).toList();
        int cursor=0, rosterIndex=0;
        // Each warden replaces four ordinary spawns: fewer bodies, the same total HP and gold.
        // Keep them separated in time; the remaining escorts retain their original spawn times.
        for(int i=0;i<regular.size();) {
            int start=(cursor+1)*regular.size()/(theme.wardens()+1)-2;
            if(cursor<theme.wardens() && i==start) {
                double health=0; double reward=0;
                for(int j=0;j<4;j++) { health+=regular.get(i+j).enemy().health(); reward+=regular.get(i+j).enemy().reward(); }
                result.add(new Wave.Entry(regular.get(i).offsetTick(),new EnemySpawn(EnemyType.WARDEN,health,1.2,reward,false)));
                i+=4;cursor++;
            } else {
                Wave.Entry entry=regular.get(i++);EnemySpawn enemy=entry.enemy();
                EnemyType type=theme.roster().get(rosterIndex++%theme.roster().size());
                result.add(new Wave.Entry(entry.offsetTick(),new EnemySpawn(type,enemy.health(),enemy.speed(),enemy.reward(),false)));
            }
        }
        for(Wave.Entry entry:budget) if(entry.enemy().boss()) {
            EnemySpawn enemy=entry.enemy();
            result.add(new Wave.Entry(entry.offsetTick(),new EnemySpawn(theme.boss(),enemy.health(),enemy.speed(),enemy.reward(),true)));
        }
        result.sort(Comparator.comparingInt(Wave.Entry::offsetTick));
        return new Wave(round,theme.displayName(),result);
    }
}
