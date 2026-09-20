package dev.moma.core;

import java.util.*;

/** Stable IDs and thresholds; counters are exact-grade lifetime counts, not inventory totals. */
public final class AchievementCatalog {
    public enum Metric { ROUND, EPIC, MYTHIC, PRIMORDIAL, TRUE_PRIMORDIAL }
    public record Entry(String id, Metric metric, long target, String title, boolean challenge) {
        public String description() {
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
        ALL = List.copyOf(entries);
    }
    private static void add(List<Entry> entries, Metric metric, long[] targets, String[] titles, long hardFrom) {
        for(int i=0;i<targets.length;i++) entries.add(new Entry(metric.name().toLowerCase(Locale.ROOT)+"_"+targets[i],metric,targets[i],titles[i],targets[i]>=hardFrom));
    }
    private AchievementCatalog() {}
}
