package dev.moma.core;

import java.util.List;

public record Wave(int round, String name, List<Entry> entries) {
    public record Entry(int offsetTick, EnemySpawn enemy) {}
    public Wave { entries = List.copyOf(entries); }
}
