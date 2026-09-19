package dev.moma.core;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.*;

/** One synchronous FFM call per arena combat tick. No entity objects cross the native boundary. */
final class NativeCombat {
    private record Library(MethodHandle batch, java.lang.foreign.Arena lifetime) {}
    private static final Library LIBRARY = load();
    private final java.lang.foreign.Arena memory = java.lang.foreign.Arena.ofAuto();
    private final ArrayList<Defender> ready = new ArrayList<>();
    private MemorySegment enemies, defenders, output;
    private int enemyCapacity, defenderCapacity;
    private boolean failed;
    private long completedBatches;
    long completedBatches() { return completedBatches; }

    static NativeCombat create() { return LIBRARY == null ? null : new NativeCombat(); }
    static boolean available() { return LIBRARY != null; }

    private static Library load() {
        String configured = System.getProperty("mud.native.library", "");
        if (configured.isBlank()) return null;
        try {
            var lifetime = java.lang.foreign.Arena.ofAuto();
            var lookup = SymbolLookup.libraryLookup(Path.of(configured).toAbsolutePath(), lifetime);
            var linker = Linker.nativeLinker();
            var abi = linker.downcallHandle(lookup.find("mud_combat_abi").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            if ((int) abi.invokeExact() != 1) throw new IllegalStateException("Unsupported MUD combat ABI");
            var batch = linker.downcallHandle(lookup.find("mud_combat_batch").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            System.getLogger(NativeCombat.class.getName()).log(System.Logger.Level.INFO, "MUD Rust combat ABI 1 loaded: " + configured);
            return new Library(batch, lifetime);
        } catch (Throwable error) {
            System.getLogger(NativeCombat.class.getName()).log(System.Logger.Level.WARNING, "MUD Rust combat unavailable; using Java", error);
            return null;
        }
    }

    boolean tick(Arena arena, long tick, List<Enemy> ordered, CombatEngine.HitSink sink) {
        if (failed || ordered.size() > 4096) return false;
        ready.clear();
        for (Defender d : arena.defenderView()) if (tick >= d.nextAttackTick()) ready.add(d);
        int n = ordered.size(), m = ready.size();
        if (n == 0 || m == 0) return true;
        ensureCapacity(n, m);
        Route route = arena.grid().route();
        for (int e = 0; e < n; e++) {
            Enemy enemy = ordered.get(e); Point p = enemy.position(route);
            put(enemies, e*4, p.x()); put(enemies, e*4+1, p.z());
            put(enemies, e*4+2, enemy.health()); put(enemies, e*4+3, enemy.boss() ? 1 : 0);
        }
        for (int i = 0; i < m; i++) {
            Defender d = ready.get(i); CombatProfile p = d.profile(); Point at = d.position(); int level = d.rarity().abilityLevel();
            int previous = -1;
            for (int e = 0; e < n; e++) if (ordered.get(e).entityId().equals(d.lastTarget())) { previous = e; break; }
            int o = i*10;
            put(defenders,o,at.x()); put(defenders,o+1,at.z()); put(defenders,o+2,p.range());
            put(defenders,o+3,AttackGeometry.areaRadius(d, p));
            put(defenders,o+4,p.damage()); put(defenders,o+5,d.type().role().ordinal()); put(defenders,o+6,level);
            put(defenders,o+7,p.targets()+level); put(defenders,o+8,d.consecutiveHits()); put(defenders,o+9,previous);
        }
        try {
            int status = (int) LIBRARY.batch.invokeExact(enemies, n, defenders, m, output);
            if (status != 0) throw new IllegalStateException("Rust combat rejected batch: " + status);
            // Validate the whole result before changing state, so fallback cannot double-apply hits.
            for (int i = 0; i < m; i++) {
                double primary = get(output, i*(n+1));
                if (!Double.isFinite(primary) || primary < -1 || primary >= n || primary != Math.rint(primary)) throw new IllegalStateException("Invalid native target");
                if (primary >= 0 && get(output, i*(n+1)+1+(int)primary) < 0) throw new IllegalStateException("Missing primary damage");
                for (int e = 0; e < n; e++) {
                    double damage = get(output, i*(n+1)+1+e);
                    if (!Double.isFinite(damage) || damage < 0 && damage != -1) throw new IllegalStateException("Invalid native damage");
                    if (primary < 0 && damage != -1) throw new IllegalStateException("Hit without primary");
                }
            }
        } catch (Throwable error) {
            failed = true;
            System.getLogger(NativeCombat.class.getName()).log(System.Logger.Level.WARNING, "MUD Rust combat disabled; using Java", error);
            return false;
        }
        for (int i = 0; i < m; i++) {
            int primary = (int) get(output,i*(n+1));
            if (primary < 0) continue;
            Defender defender = ready.get(i);
            defender.hitTarget(ordered.get(primary).entityId());
            apply(defender, ordered.get(primary), get(output,i*(n+1)+1+primary), tick, sink);
            for (int e = 0; e < n; e++) {
                double damage = get(output,i*(n+1)+1+e);
                if (e != primary && damage >= 0) apply(defender, ordered.get(e), damage, tick, sink);
            }
            defender.attackAt(tick, defender.profile().intervalTicks());
        }
        completedBatches++;
        return true;
    }

    private static void apply(Defender defender, Enemy enemy, double damage, long tick, CombatEngine.HitSink sink) {
        int level = defender.rarity().abilityLevel();
        if (level > 0 && defender.type().role() == AttackRole.MELEE_CLEAVE) enemy.slow(.10+.08*level,tick+40);
        double effective = Math.min(enemy.health(),damage);
        enemy.damage(damage);
        if (sink != null) sink.hit(defender,enemy,effective);
    }
    private void ensureCapacity(int n, int m) {
        if (n <= enemyCapacity && m <= defenderCapacity) return;
        enemyCapacity = Math.max(n,Math.max(100,enemyCapacity*2)); defenderCapacity = Math.max(m,Math.max(36,defenderCapacity*2));
        enemies = memory.allocate((long)enemyCapacity*4*8,8);
        defenders = memory.allocate((long)defenderCapacity*10*8,8);
        output = memory.allocate((long)defenderCapacity*(enemyCapacity+1)*8,8);
    }
    private static void put(MemorySegment segment, int index, double value) { segment.setAtIndex(ValueLayout.JAVA_DOUBLE,index,value); }
    private static double get(MemorySegment segment, int index) { return segment.getAtIndex(ValueLayout.JAVA_DOUBLE,index); }
}
