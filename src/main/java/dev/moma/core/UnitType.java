package dev.moma.core;

import static dev.moma.core.AttackRole.*;

public enum UnitType {
    WOLF("늑대", MELEE_SINGLE, 12, 16, 4, 0, 1),
    POLAR_BEAR("북극곰", MELEE_SINGLE, 25, 32, 4.2, 0, 1),
    PANDA("판다", MELEE_SINGLE, 30, 40, 4.5, 0, 1),
    RABBIT("토끼", MELEE_SINGLE, 7, 10, 3.8, 0, 1),
    IRON_GOLEM("철골렘", MELEE_CLEAVE, 16, 30, 4.5, 0, 1),
    HOGLIN("호글린", MELEE_CLEAVE, 12, 24, 4.2, 0, 1),
    GOAT("염소", MELEE_CLEAVE, 8, 16, 4, 0, 1),
    RAVAGER("파괴수", MELEE_CLEAVE, 22, 40, 4.8, 0, 1),
    SKELETON("스켈레톤", RANGED_SINGLE, 14, 24, 9, 0, 1),
    STRAY("스트레이", RANGED_SINGLE, 20, 32, 10, 0, 1),
    BOGGED("보그드", RANGED_SINGLE, 10, 18, 8, 0, 1),
    PIGLIN("피글린", RANGED_SINGLE, 26, 40, 11, 0, 1),
    WITCH("마녀", SMALL_AREA, 12, 30, 8, 1.8, 1),
    LLAMA("라마", SMALL_AREA, 7, 20, 7, 1.5, 1),
    SNOW_GOLEM("눈골렘", SMALL_AREA, 5, 14, 8, 1.4, 1),
    GUARDIAN("가디언", SMALL_AREA, 18, 40, 9, 2, 1),
    BLAZE("블레이즈", LARGE_AREA, 10, 36, 8, 3, 1),
    GHAST("가스트", LARGE_AREA, 18, 60, 10, 3.5, 1),
    WITHER_SKELETON("위더 스켈레톤", LARGE_AREA, 7, 28, 7, 2.8, 1),
    WARDEN("워든", LARGE_AREA, 24, 72, 9, 4, 1),
    EVOKER("소환사", MULTI_TARGET, 8, 30, 8, 0, 3),
    SHULKER("셜커", MULTI_TARGET, 12, 40, 9, 0, 3),
    ALLAY("알레이", MULTI_TARGET, 4, 16, 7, 0, 3),
    VEX("벡스", MULTI_TARGET, 6, 24, 8, 0, 4);

    private final String label;
    private final AttackRole role;
    private final CombatProfile profile;
    UnitType(String label, AttackRole role, double damage, int interval, double range, double radius, int targets) {
        this.label = label; this.role = role;
        this.profile = new CombatProfile(damage, interval, range, radius, targets);
    }
    public String label() { return label; }
    public AttackRole role() { return role; }
    public CombatProfile profile() { return profile; }
}
