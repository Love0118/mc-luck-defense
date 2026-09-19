package dev.moma.paper;

import org.bukkit.configuration.file.FileConfiguration;

record DevelopmentSettings(int gridSize, long startingCoins, int enemyLimit, double enemyHealth, double enemySpeed, long reward) {
    static DevelopmentSettings load(FileConfiguration config) {
        var s = new DevelopmentSettings(config.getInt("development.grid-size"), config.getLong("development.starting-coins"),
                config.getInt("development.enemy-limit"), config.getDouble("development.enemy-health"),
                config.getDouble("development.enemy-speed"), config.getLong("development.kill-reward"));
        if (s.gridSize < 2 || s.gridSize > 15 || s.startingCoins < 0 || s.startingCoins > 1_000_000
                || s.enemyLimit < 2 || s.enemyLimit > 1000 || !Double.isFinite(s.enemyHealth) || s.enemyHealth <= 0
                || !Double.isFinite(s.enemySpeed) || s.enemySpeed <= 0 || s.enemySpeed > 20 || s.reward < 0 || s.reward > 1_000_000)
            throw new IllegalArgumentException("config.yml의 development 수치가 허용 범위를 벗어났습니다.");
        return s;
    }
}
