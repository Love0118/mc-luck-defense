package dev.moma.paper;

import dev.moma.bgm.BgmLimits;
import org.bukkit.configuration.file.YamlConfiguration;

final class BgmSettings {
    private BgmSettings() {}
    static BgmLimits limits(YamlConfiguration config) {
        BgmLimits defaults = BgmLimits.DEFAULT;
        return new BgmLimits(positive(config, "limits.uploads-per-player", defaults.uploadsPerPlayer()),
                positive(config, "limits.playlist-tracks", defaults.playlistTracks()),
                positive(config, "limits.duration-seconds", defaults.durationSeconds()),
                positive(config, "limits.file-size-mb", defaults.fileSizeMb()));
    }
    private static int positive(YamlConfiguration config, String key, int fallback) {
        if (!config.contains(key)) { config.set(key, fallback); return fallback; }
        Object value = config.get(key);
        if (!(value instanceof Integer number) || number < 1)
            throw new IllegalArgumentException(key + " must be a positive integer");
        return number;
    }
}
