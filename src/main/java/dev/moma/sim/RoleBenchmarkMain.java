package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;

/** Equal rarity, equal exposure, best fixed legal cell; effective damage excludes overkill. */
public final class RoleBenchmarkMain {
    private RoleBenchmarkMain() {}
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/role-benchmark.jsonl" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        var rows = new ArrayList<String>();
        for (Rarity rarity : Rarity.values()) {
            for (UnitType type : UnitType.values()) {
                double boss = trial(type, rarity, false), crowd = trial(type, rarity, true);
                rows.add(String.format(Locale.ROOT, "{\"unit\":\"%s\",\"role\":\"%s\",\"rarity\":\"%s\",\"bossDps\":%.5f,\"crowdDps\":%.5f}", type, type.role(), rarity, boss, crowd));
            }
        }
        Files.write(output, rows);
        System.out.println(output);
    }
    private static double trial(UnitType type, Rarity rarity, boolean crowd) {
        UUID owner = new UUID(0, 0);
        Arena arena = new Arena("benchmark", owner, new Grid(CampaignRules.standard().gridSize()), 10, 1000);
        arena.summon(owner, new SummonRoll(type, rarity), (t, r, c) -> new UUID(0, 1));
        Defender defender = arena.defenders().getFirst();
        Cell best = defender.cell(); int max = -1;
        for (Cell cell : arena.grid().placementOrder()) {
            int covered = 0;
            for (int i = 0; i < 144; i++) if (cell.point().distanceSquared(arena.grid().route().at(i * arena.grid().route().length() / 144)) <= defender.profile().range() * defender.profile().range()) covered++;
            if (covered > max) { best = cell; max = covered; }
        }
        if (!best.equals(defender.cell())) { arena.select(owner, defender.entityId()); arena.moveSelected(owner, best); }
        CombatEngine engine = new CombatEngine(); double[] damage = {0}; long sequence = 1;
        for (int tick = 0; tick < 3600; tick++) {
            if (tick == 0 || crowd && tick % 16 == 0 && arena.enemyCount() < 48) arena.addEnemy(new Enemy(new UUID(0, ++sequence), arena.id(), EnemyType.ZOMBIE, 1e9, 2, 0, !crowd));
            engine.tick(arena, tick, (d, e, amount) -> { damage[0] += amount; });
        }
        return damage[0] / 180;
    }
}
