package dev.moma.mining;

import java.util.*;
import java.util.random.RandomGenerator;

/** Server-independent limits and reward arithmetic. Cargo never comes from player inventories. */
final class MiningRules {
    static final int SPEED_CAP = 200, FORTUNE_CAP = 3;
    enum Upgrade { SPEED, FORTUNE }
    enum Outcome { SUCCESS, FAILURE, DESTROYED }
    record Offer(long cost,int success,int destruction) {}
    record Attempt(Outcome outcome,int gain) {}
    enum Ore {
        COAL("석탄", 1, 28), COPPER("구리", 1, 23), IRON("철", 2, 20),
        GOLD("금", 3, 10), REDSTONE("레드스톤", 1, 8), LAPIS("청금석", 2, 6),
        DIAMOND("다이아몬드", 4, 4), EMERALD("에메랄드", 5, 1);
        final String label;
        final int price, weight;
        Ore(String label, int price, int weight) { this.label = label; this.price = price; this.weight = weight; }
        String block() { return name() + "_ORE"; }
        static Ore fromBlock(String block) {
            for (Ore ore : values()) if (ore.block().equals(block)) return ore;
            return null;
        }
    }
    private MiningRules() {}
    static float speed(int level) {
        if (level < 50 || level > SPEED_CAP) throw new IllegalArgumentException("Invalid speed percent");
        return 6f * level / 100f;
    }
    static int cap(Upgrade upgrade) { return upgrade == Upgrade.SPEED ? SPEED_CAP : FORTUNE_CAP; }
    static long price(long configured) { return Math.clamp(configured, 0, 5); }
    static Offer offer(Upgrade upgrade,int current) {
        if(upgrade==Upgrade.FORTUNE){
            if(current<0||current>=FORTUNE_CAP)throw new IllegalArgumentException("최대 강화입니다.");
            return switch(current){case 0->new Offer(600,80,0);case 1->new Offer(1500,60,5);default->new Offer(3500,40,12);};
        }
        if(current<50||current>=SPEED_CAP)throw new IllegalArgumentException("최대 강화입니다.");
        int band=(current-50)/25;
        return new Offer(new long[]{150,250,500,800,1300,2000}[band],90-band*10,new int[]{0,0,3,6,10,15}[band]);
    }
    static Attempt roll(Upgrade upgrade,int current,RandomGenerator random){
        var offer=offer(upgrade,current);int draw=random.nextInt(100);
        if(draw<offer.success())return new Attempt(Outcome.SUCCESS,upgrade==Upgrade.SPEED?Math.min(200-current,random.nextInt(1,11)):1);
        return new Attempt(draw<offer.success()+offer.destruction()?Outcome.DESTROYED:Outcome.FAILURE,0);
    }
    static boolean depleted(int remaining, int initial, double threshold) {
        return initial > 0 && remaining < initial * Math.clamp(threshold, .05, .75);
    }
    static Ore randomOre(RandomGenerator random) {
        int draw = random.nextInt(100);
        for (Ore ore : Ore.values()) { draw -= ore.weight; if (draw < 0) return ore; }
        throw new AssertionError("Ore weights must sum to 100");
    }
    static int drops(Ore ore, int fortune, RandomGenerator random) {
        if (fortune < 0 || fortune > FORTUNE_CAP) throw new IllegalArgumentException("Invalid fortune");
        if (ore == Ore.REDSTONE) return 4 + random.nextInt(2) + random.nextInt(fortune + 1);
        int base = ore == Ore.COPPER ? 2 + random.nextInt(4) : ore == Ore.LAPIS ? 4 + random.nextInt(6) : 1;
        int multiplier = fortune == 0 ? 1 : Math.max(1, random.nextInt(fortune + 2));
        return base * multiplier;
    }
    static long sale(Map<Ore, Long> cargo, Map<Ore, Long> prices) {
        long total = 0;
        for (Ore ore : Ore.values()) {
            long count = cargo.getOrDefault(ore, 0L);
            if (count < 0) throw new IllegalArgumentException("Negative cargo");
            total = Math.addExact(total, Math.multiplyExact(count, price(prices.getOrDefault(ore, (long) ore.price))));
        }
        return total;
    }
}
