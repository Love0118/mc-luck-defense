package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;

public final class WaveExportMain {
    private WaveExportMain() {}
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/waves.json" : args[0]);
        var rows = new ArrayList<String>();
        for (Wave wave : WaveSchedule.create(CampaignRules.standard())) {
            var groups = new LinkedHashMap<EnemySpawn, Integer>();
            for (Wave.Entry entry : wave.entries()) groups.merge(entry.enemy(), 1, Integer::sum);
            var enemies = new ArrayList<String>();
            for (var entry : groups.entrySet()) {
                EnemySpawn e = entry.getKey();
                enemies.add(String.format(Locale.ROOT, "{\"type\":\"%s\",\"count\":%d,\"health\":%.4f,\"speed\":%.2f,\"reward\":%d,\"boss\":%s}",
                        e.type(), entry.getValue(), e.health(), e.speed(), e.reward(), e.boss()));
            }
            rows.add("{\"round\":" + wave.round() + ",\"name\":\"" + wave.name() + "\",\"enemies\":[" + String.join(",", enemies) + "]}");
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, "[\n" + String.join(",\n", rows) + "\n]\n");
    }
}
