package dev.moma.core;

import java.util.*;

/** Stable IDs and thresholds; counters are exact-grade lifetime counts, not inventory totals. */
public final class AchievementCatalog {
    public enum Metric {
        ROUND, EPIC, MYTHIC, PRIMORDIAL, TRUE_PRIMORDIAL, ENHANCEMENT, SESSION,
        ROLE_MELEE_SINGLE, ROLE_MELEE_CLEAVE, ROLE_RANGED_SINGLE, ROLE_SMALL_AREA, ROLE_LARGE_AREA, ROLE_MULTI_TARGET;
        public boolean role() { return name().startsWith("ROLE_"); }
        public AttackRole attackRole() { return AttackRole.valueOf(name().substring(5)); }
    }
    public record Entry(String id, Metric metric, long target, String title, boolean challenge) implements java.io.Serializable {
        public String description() {
            if(metric.role())return "150라운드 도달 · "+metric.attackRole().label()+" 유효 피해 비중 70% 이상";
            if(metric==Metric.ENHANCEMENT)return "누적 동일 유닛 합성 "+target+"회";
            if(metric==Metric.SESSION)return "누적 게임 시작 "+target+"회";
            return metric == Metric.ROUND ? target + "라운드 도달"
                    : metric == Metric.TRUE_PRIMORDIAL ? "누적 진 태초 " + target + "회 승급"
                    : "누적 " + Rarity.valueOf(metric.name()).label() + " " + target + "회 소환";
        }
    }
    public static final List<Entry> ALL;
    static {
        List<Entry> entries = new ArrayList<>();
        add(entries, Metric.ROUND, new long[]{1,5,10,20,30,50,70,90,100,125,150,200,250,300,400,500,750,1000,1500,2000},
                new String[]{"첫 출전","몸풀기 완료","첫 고비","전선 유지","반환점의 문턱","절반의 여정","정예 수비대","끝을 향하여","백전의 수호자","한계 너머","멈추지 않는 수비","이백의 성벽","불굴의 진형","삼백의 전설","무너질 수 없는 탑","오백의 신화","끝없는 전장","천 라운드의 지배자","시간을 넘은 수호자","영원의 디펜스"},100);
        add(entries, Metric.EPIC, new long[]{1,5,10,25,50,100,250,500,1000,2500},
                new String[]{"에픽의 빛","에픽 수집가","열 번의 기적","빛나는 명단","에픽 부대","백 개의 별","별을 모으는 자","에픽 도서관","천 개의 빛","에픽의 기록자"},100);
        add(entries, Metric.MYTHIC, new long[]{1,3,5,10,25,50,100,250,500,1000},
                new String[]{"신화의 시작","세 편의 신화","신화 원정대","신화 수집가","신화의 계보","신화의 전당","백 가지 신화","신화의 서고","신화의 주인","천 년의 신화"},10);
        add(entries, Metric.PRIMORDIAL, new long[]{1,2,3,5,10,20,30,50,75,100},
                new String[]{"태초의 순간","두 개의 기원","삼중의 기적","태초의 손길","기원의 수집가","스무 번의 탄생","태초의 별자리","기원의 지배자","세상 이전의 기록","백 번의 태초"},1);
        add(entries, Metric.TRUE_PRIMORDIAL, new long[]{1,2,3,5,10,20,30,50,75,100},
                new String[]{"기원을 넘어","두 번의 초월","진정한 삼위","초월의 손길","진 태초 수집가","기원 너머의 군단","초월의 별자리","진 태초의 지배자","시작 이전의 힘","백 번의 초월"},1);
        add(entries, Metric.ROUND, new long[]{350,600,700},new String[]{"선택의 확장","육백의 방벽","칠백의 증명"},0);
        add(entries, Metric.SESSION, new long[]{1,10,50,100,250,500,1000},
                new String[]{"새로운 도전","다시 출발","도전의 습관","백 번의 출전","끊이지 않는 도전","오백 번의 결심","천 번의 재회"},100);
        add(entries, Metric.ENHANCEMENT, new long[]{100,500,2000,10000,50000},
                new String[]{"단련의 시작","숙련된 대장장이","강화의 장인","끝없는 단련","완성된 담금질"},0);
        String[] roleTitles={"결투의 지휘관","전선의 지휘관","저격의 지휘관","집중 포화","전장의 폭풍","동시 제압"};
        int roleIndex=0;
        for(Metric metric:Metric.values())if(metric.role())
            add(entries,metric,new long[]{150},new String[]{roleTitles[roleIndex++]},0);
        entries.sort(Comparator.comparing(Entry::metric).thenComparingLong(Entry::target));
        ALL = List.copyOf(entries);
    }
    private static void add(List<Entry> entries, Metric metric, long[] targets, String[] titles, long hardFrom) {
        for(int i=0;i<targets.length;i++) entries.add(new Entry(metric.name().toLowerCase(Locale.ROOT)+"_"+targets[i],metric,targets[i],titles[i],targets[i]>=hardFrom));
    }
    private AchievementCatalog() {}
}
