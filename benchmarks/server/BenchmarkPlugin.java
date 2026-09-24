package dev.moma.benchmark;

import dev.moma.core.*;
import dev.moma.sim.AutoPlayer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;

/** Isolated test plugin, never bundled in MCLuckDefense. Exercises the actual GameService. */
public final class BenchmarkPlugin extends JavaPlugin {
    private final int sessions = Integer.getInteger("mudbench.sessions", 20);
    private final int warmup = Integer.getInteger("mudbench.warmup", 6400);
    private final int measure = Integer.getInteger("mudbench.measure", 19200);
    private final boolean campaignMode = Boolean.getBoolean("mudbench.campaign");
    private final int targetTps = Integer.getInteger("mudbench.targetTps", 320);
    private final int sessionSpeed = Integer.getInteger("mudbench.sessionSpeed", 1);
    private final List<Object> gameSessions = new ArrayList<>();
    private final List<AutoPlayer> bots = new ArrayList<>();
    private int peakEnemies, peakDefenders;
    private final List<Arena> arenas = new ArrayList<>();
    private final List<Double> mspt = new ArrayList<>();
    private final List<Double> windows = new ArrayList<>();
    private int tick = -1, setupIndex;
    private long started, windowStart;
    private Object games, maps, adapter, minecraftServer;
    private Method getTickCount, getTimes;
    private Recording recording;
    private long heapBefore, gcBefore;

    @Override public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1")) throw new IllegalStateException("Benchmark requires localhost binding");
        getServer().getScheduler().runTaskTimer(this, () -> {
            try { run(); } catch (Throwable error) { getLogger().log(java.util.logging.Level.SEVERE, "Benchmark failed", error); Bukkit.shutdown(); }
        }, 1, 1);
    }
    private void run() throws Exception {
        if (tick < 0) {
            if (Bukkit.getOnlinePlayers().size() != sessions) return;
            if (games == null) {
                var plugin = Bukkit.getPluginManager().getPlugin("MCLuckDefense");
                games = field(plugin, "games"); maps = field(games, "maps"); adapter = field(games, "entities");
                minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                getTickCount = minecraftServer.getClass().getMethod("getTickCount");
                getTimes = minecraftServer.getClass().getMethod("getTickTimesNanos");
            }
            if (setupIndex < sessions) { prepare(setupIndex++); return; }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick rate " + targetTps);
            tick = 0;
            getLogger().info("BENCH_WARMUP sessions=" + sessions + " mode=" + (campaignMode ? "campaign" : "dense"));
            return;
        }
        for (Arena arena : arenas) if (!campaignMode && (arena.ended() || arena.enemyCount() != 90 || arena.defenderCount() != 36))
            throw new IllegalStateException("Fixture changed: " + arena.id() + " " + arena.outcome() + " " + arena.enemyCount());
        if (Bukkit.getOnlinePlayers().size() != sessions) throw new IllegalStateException("Lost load client");
        if (tick % 320 == 0) for (int i = 0; i < arenas.size(); i++) {
            if (arenas.get(i).ended()) continue; // Finished sessions now remove their presentation entities.
            Object map = field(gameSessions.get(i), "map");
            for (Enemy enemy : arenas.get(i).activeEnemies()) {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(enemy.entityId());
                Location expected = (Location) call(map,"location",enemy.position(arenas.get(i).grid().route()));
                if (entity == null || Math.abs(entity.getX()-expected.getX()) > 1e-8 || Math.abs(entity.getY()-expected.getY()) > 1e-8 || Math.abs(entity.getZ()-expected.getZ()) > 1e-8)
                    throw new IllegalStateException("Entity/core position mismatch");
            }
        }
        if (tick == Math.max(0, warmup - 320)) {
            recording = new Recording(jdk.jfr.Configuration.getConfiguration("profile")); recording.start();
        }
        if (tick == warmup) {
            if (campaignMode) for (Object session : gameSessions) set(session, "campaign", new Campaign(CampaignRules.standard()));
            started = windowStart = System.nanoTime(); gcBefore = gcMillis(); heapBefore = usedHeap();
            getLogger().info("BENCH_MEASURE");
        }
        if (campaignMode && tick >= warmup) for (int i = 0; i < sessions; i++) bots.get(i).act(arenas.get(i), tick-warmup);
        peakEnemies = Math.max(peakEnemies, arenas.stream().mapToInt(Arena::enemyCount).sum());
        peakDefenders = Math.max(peakDefenders, arenas.stream().mapToInt(Arena::defenderCount).sum());
        if (tick > warmup && (tick - warmup) % 6400 == 0) getLogger().info("BENCH_PROGRESS tick=" + (tick-warmup)
            + " active=" + arenas.stream().filter(a -> !a.ended()).count() + " enemies=" + arenas.stream().mapToInt(Arena::enemyCount).sum());
        if (tick > warmup) {
            int count = (int) getTickCount.invoke(minecraftServer);
            long[] times = (long[]) getTimes.invoke(minecraftServer);
            mspt.add(times[Math.floorMod(count - 1, times.length)] / 1e6);
            if ((tick - warmup) % 320 == 0) {
                long now = System.nanoTime(); windows.add(320e9 / (now - windowStart)); windowStart = now;
            }
        }
        if (tick++ == warmup + measure) finish();
    }
    private void prepare(int index) throws Exception {
        Player player = Bukkit.getPlayerExact(String.format(Locale.ROOT, "MudBench%02d", index));
        if (player == null) throw new IllegalStateException("Missing client " + index);
        String id = "bench" + index;
        Object map = call(maps, "get", id);
        if (map == null) map = call(maps, "create", id, 6);
        call(games, "join", player, id);
        call(games, "speed", player, sessionSpeed);
        Object session = call(games, "session", player);
        gameSessions.add(session);
        Arena arena = (Arena) field(session, "arena"); arenas.add(arena);
        var r = CampaignRules.standard();
        set(session, "campaign", new Campaign(new CampaignRules(6, 100, 100, 1_000_000, r.roundTicks(), r.cleanupTicks(), r.healthScale(), r.healthCurve(), r.bossHealthScale(), r.endlessHealthPower(), r.endlessPressureBend())));
        if (campaignMode) {
            final Object arenaMap = map;
            // Same clear-capable seed per session sustains a full campaign load; not a win-rate sample.
            bots.add(new AutoPlayer(100147, AutoPlayer.Strategy.BALANCED, arena.grid(), (type, rarity, cell) -> {
                try { return (UUID) call(adapter, "spawnDefender", arenaMap, player.getUniqueId(), type, rarity, cell); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }, entity -> {
                try { call(adapter, "remove", entity); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }, Integer.MAX_VALUE));
            return;
        }
        arena.credit(1000);
        final Object arenaMap = map;
        for (int i = 0; i < 36; i++) {
            var type = UnitType.values()[i % 24];
            var rarity = i < 2 ? Rarity.PRIMORDIAL : i < 7 ? Rarity.MYTHIC : Rarity.LEGENDARY;
            arena.summon(player.getUniqueId(), new SummonRoll(type, rarity), (t, tier, cell) -> {
                try { return (UUID) call(adapter, "spawnDefender", arenaMap, player.getUniqueId(), t, tier, cell); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
        }
        call(games, "select", player, arena.defenders().getFirst().entityId());
        var progress = Enemy.class.getDeclaredField("progress"); progress.setAccessible(true);
        for (int i = 0; i < 90; i++) {
            EnemyType type = EnemyType.values()[i % 6];
            UUID entity = (UUID) call(adapter, "spawnEnemy", map, player.getUniqueId(), type, i == 0);
            Enemy enemy = new Enemy(entity, arena.id(), type, 1e15, 2 + i % 3 * .5, 0, i == 0);
            progress.setDouble(enemy, arena.grid().route().length() * i / 90.0);
            arena.addEnemy(enemy);
        }
        getLogger().info("BENCH_PREPARED " + id);
    }
    private void finish() throws Exception {
        double seconds = (System.nanoTime() - started) / 1e9;
        recording.stop(); recording.dump(getDataFolder().toPath().resolve("profile.jfr")); recording.close();
        double[] values = mspt.stream().mapToDouble(x -> x).sorted().toArray();
        long missed = mspt.stream().filter(x -> x > 1000.0/targetTps).count();
        String json = String.format(Locale.ROOT,
            "{\"sessions\":%d,\"viewers\":%d,\"defenders\":%d,\"enemies\":%d,\"targetTps\":320,\"warmupTicks\":%d,\"measuredTicks\":%d,\"seconds\":%.6f,\"actualTps\":%.4f,\"meanMspt\":%.6f,\"p50Mspt\":%.6f,\"p95Mspt\":%.6f,\"p99Mspt\":%.6f,\"maxMspt\":%.6f,\"ticksOverBudget\":%d,\"gcMillis\":%d,\"heapDelta\":%d,\"tpsWindows\":%s,\"mspt\":%s}\n",
            sessions, Bukkit.getOnlinePlayers().size(), peakDefenders, peakEnemies, warmup, measure, seconds, measure/seconds,
            Arrays.stream(values).average().orElse(0), percentile(values,.5), percentile(values,.95), percentile(values,.99), values[values.length-1],
            missed, gcMillis()-gcBefore, usedHeap()-heapBefore, windows, mspt);
        json = json.replace("\"targetTps\":320", "\"targetTps\":" + targetTps + ",\"sessionSpeed\":" + sessionSpeed);
        Files.writeString(getDataFolder().toPath().resolve("result.json"), json);
        var outcomes = new ArrayList<String>();
        for (int i = 0; i < sessions; i++) outcomes.add("{\"outcome\":\""+arenas.get(i).outcome()+"\",\"round\":"+((Campaign)field(gameSessions.get(i),"campaign")).round()+"}");
        Files.writeString(getDataFolder().toPath().resolve("workload.json"), "{\"mode\":\""+(campaignMode?"campaign":"dense")+"\",\"peakEnemies\":"+peakEnemies+",\"peakDefenders\":"+peakDefenders+",\"outcomes\":["+String.join(",",outcomes)+"]}");
        getLogger().info("BENCH_DONE actualTps=" + measure/seconds + " meanMspt=" + Arrays.stream(values).average().orElse(0));
        tick = Integer.MIN_VALUE;
        Bukkit.shutdown();
    }
    @Override public void onLoad() { getDataFolder().mkdirs(); }
    private static double percentile(double[] v, double p) { return v[Math.min(v.length-1,(int)Math.ceil(v.length*p)-1)]; }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(); }
    private static long gcMillis() { return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum(); }
    private static Object field(Object o, String name) throws Exception { Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static void set(Object o, String name, Object value) throws Exception { Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o,value); }
    private static Object call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount()==args.length) { m.setAccessible(true); return m.invoke(o,args); }
        throw new NoSuchMethodException(name);
    }
}
