package dev.moma.sim;

import dev.moma.core.*;

/** Export the actual catalog, without duplicating thresholds in a documentation script. */
public final class AchievementExportMain {
    public static void main(String[] args) {
        System.out.println("# 서버 도전과제 · "+AchievementCatalog.ALL.size()+"개");
        System.out.println("\n챌린지 업적마다 특성 하나를 해금합니다. 총 "+TraitCatalog.ALL.size()+"개 특성이며 최대 3개를 장착합니다.");
        System.out.println("\n[특성 사용법과 검증](traits.md)");
        System.out.println("\n기존 누적 소환·최고 라운드 기록은 유지합니다. 특성으로 올라간 등급은 자연 소환 횟수로 세지 않습니다.");
        System.out.println("\n강화는 성공한 중복 구매에 의한 합성 횟수입니다. 승급 연쇄 흡수는 추가로 세지 않으며, 업데이트 이전 합성·역할별 피해 기록은 소급하지 않습니다.");
        System.out.println("\n| ID | 이름 | 조건 | 보상 특성 | 효과 |");
        System.out.println("|---|---|---|---|---|");
        for(var achievement:AchievementCatalog.ALL) {
            var trait=TraitCatalog.find(achievement.id());
            System.out.println("| "+achievement.id()+" | "+achievement.title()+" | "+achievement.description()+" | "+
                    (trait==null?"없음":trait.name())+" | "+(trait==null?"일반 토스트":trait.description())+" |");
        }
        System.out.println("\n챌린지는 기본 완료음을 사용합니다. 관리자 개입 판의 이후 진행·관전·실패 구매는 업적에 반영하지 않습니다.");
    }
    private AchievementExportMain() {}
}
