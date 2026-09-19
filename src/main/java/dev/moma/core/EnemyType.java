package dev.moma.core;

public enum EnemyType {
    ZOMBIE("좀비"), HUSK("허스크"), DROWNED("드라운드"),
    SPIDER("거미"), SLIME("슬라임"), MAGMA_CUBE("마그마 큐브");
    private final String label;
    EnemyType(String label) { this.label = label; }
    public String label() { return label; }
}
