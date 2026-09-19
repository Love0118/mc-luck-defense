package dev.moma.core;

public enum EnemyType {
    ZOMBIE("좀비"), HUSK("허스크"), DROWNED("드라운드"),
    SPIDER("거미"), SLIME("슬라임"), MAGMA_CUBE("마그마 큐브"),
    BEE("벌"), WOLF("늑대"), SKELETON("스켈레톤"), VINDICATOR("변명자"), CREAKING("크리킹"),
    WITCH("마녀"), FROG("개구리"), OCELOT("오실롯"), PANDA("판다"), PILLAGER("약탈자"),
    RAVAGER("파괴수"), CREEPER("크리퍼"), CAVE_SPIDER("동굴거미"), SHEEP("양"), STRAY("스트레이"),
    POLAR_BEAR("북극곰"), GOAT("염소"), LLAMA("라마"), SQUID("오징어"), TURTLE("거북"),
    COD("대구"), GUARDIAN("가디언"), TROPICAL_FISH("열대어"), PUFFERFISH("복어"), SALMON("연어"),
    ELDER_GUARDIAN("엘더 가디언"), MOOSHROOM("무시룸"), RABBIT("토끼"), AXOLOTL("아홀로틀"),
    GLOW_SQUID("발광 오징어"), WARDEN("워든"), ZOMBIFIED_PIGLIN("좀비화 피글린"), GHAST("가스트"),
    PIGLIN("피글린"), HOGLIN("호글린"), ENDERMAN("엔더맨"), PIGLIN_BRUTE("피글린 야수"),
    WITHER_SKELETON("위더 스켈레톤"), BLAZE("블레이즈"), WITHER("위더"),
    ENDERMITE("엔더마이트"), ENDER_DRAGON("엔더 드래곤"), SHULKER("셜커");
    private final String label;
    EnemyType(String label) { this.label = label; }
    public String label() { return label; }
}
