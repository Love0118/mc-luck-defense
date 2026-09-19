package dev.moma.core;

public enum AttackRole {
    MELEE_SINGLE("근거리 · 단일", "같은 대상 연속 공격 시 피해 강화"),
    MELEE_CLEAVE("근거리 · 광역", "명중한 적 감속"),
    RANGED_SINGLE("원거리 · 단일", "보스 추가 피해"),
    SMALL_AREA("원거리 · 준광역", "중심 대상 추가 피해"),
    LARGE_AREA("원거리 · 대광역", "공격 반경 증가"),
    MULTI_TARGET("원거리 · 다중", "동시 공격 대상 수 증가");

    private final String label;
    private final String ability;

    AttackRole(String label, String ability) { this.label = label; this.ability = ability; }
    public String label() { return label; }
    public String ability() { return ability; }
    public boolean melee() { return this == MELEE_SINGLE || this == MELEE_CLEAVE; }
}
