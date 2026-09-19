package dev.moma.core;

import java.util.UUID;

public record EnemySpawn(EnemyType type, double health, double speed, double reward, boolean boss) {
    public Enemy create(UUID entity, String arena) { return new Enemy(entity, arena, type, health, speed, reward, boss); }
}
