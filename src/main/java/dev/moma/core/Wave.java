package dev.moma.core;

import java.util.List;

public record Wave(int round, String name, List<Entry> entries) implements java.io.Serializable {
    public record Entry(int offsetTick, EnemySpawn enemy) implements java.io.Serializable {}
    public Wave { entries = List.copyOf(entries); }
}
