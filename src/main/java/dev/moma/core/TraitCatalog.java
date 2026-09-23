package dev.moma.core;

import java.util.*;
import dev.moma.core.AchievementCatalog.*;

/** Achievement-backed rewards; equipped families use one slot and opening rewards are passive. */
public final class TraitCatalog {
    public enum Family { START_GOLD, FIRST_PURCHASE, OPENING_ODDS, DAMAGE, NORMAL_DAMAGE, SPEED, BOSS_DAMAGE, CRITICAL, ROLE_DAMAGE, ENHANCEMENT, SPENDING_DAMAGE, DUPLICATE_ODDS, GOLD_INCOME }
    public record Entry(String id, String name, Family family, int value, AttackRole role, Rarity purchaseCeiling, Rarity openingTarget, AchievementCatalog.Entry achievement) implements java.io.Serializable {
        public boolean passive() { return family==Family.FIRST_PURCHASE || family==Family.OPENING_ODDS; }
        public String description() {
            return switch(family) {
                case START_GOLD -> "시작 골드 +"+value;
                case FIRST_PURCHASE -> (value==1?"첫 성공 구매":"처음 "+value+"번 성공 구매")+" · "+purchaseCeiling.label()+" 이하 등급 +1";
                case OPENING_ODDS -> "첫 3회 소환 · "+openingTarget.label()+" 확률 "+java.math.BigDecimal.valueOf(value,3).stripTrailingZeros().toPlainString()+"%";
                case DAMAGE -> "모든 아군 피해 +"+value+"%";
                case NORMAL_DAMAGE -> "일반 적 피해 +"+value+"%";
                case BOSS_DAMAGE -> "보스 피해 +"+value+"%";
                case SPEED -> "공격속도 +"+value+"%";
                case CRITICAL -> "치명타 확률 "+value+"% · 피해 1.5배";
                case ROLE_DAMAGE -> role.label()+" 피해 +"+value+"%";
                case ENHANCEMENT -> "강화 +1당 피해 성장 +"+value+"%p";
                case SPENDING_DAMAGE, GOLD_INCOME -> "적 처치 골드 +"+value+"%";
                case DUPLICATE_ODDS -> "가장 강화수치가 높은 기물 등장확률 "+value+"%p 증가";
            };
        }
    }
    public static final List<Entry> ALL;
    private static final Map<String,Entry> BY_ID;
    static {
        List<Entry> entries=new ArrayList<>();
        for(var a:AchievementCatalog.ALL) {
            if(!a.challenge())continue;
            Family family; int value; String name; AttackRole role=null;Rarity ceiling=Rarity.LEGENDARY,target=null;
            if(a.metric()==Metric.ROUND) {
                int round=(int)a.target();
                if(Set.of(100,150,400,750).contains(round)) {
                    family=Family.START_GOLD;value=switch(round){case 100->10;case 150->15;case 400->20;default->25;};name="출전 수당";
                } else if(Set.of(125,250,350).contains(round)) {
                    family=Family.FIRST_PURCHASE;value=round==125?1:2;
                    ceiling=round==350?Rarity.EPIC:Rarity.LEGENDARY;
                    name=switch(round){case 125->"첫 인연";case 250->"준비된 인연";default->"신화의 인연";};
                } else {
                    family=Family.DAMAGE;name="수비 교본";
                    value=switch(round){case 200->4;case 300->5;case 500->6;case 600->7;case 700->8;case 1000->9;case 1500->10;case 2000->12;case 2250->13;case 2500->14;case 3000->15;case 4000->16;case 5000->17;case 6000->18;case 7500->19;case 9000->20;case 10000->21;default->throw new IllegalStateException(a.id());};
                }
            } else if(a.metric().role()) {
                family=Family.ROLE_DAMAGE;value=12;role=a.metric().attackRole();name=a.title()+" 교본";
            } else {
                int index=AchievementCatalog.ALL.stream().filter(e->e.metric()==a.metric() && e.challenge()).toList().indexOf(a);
                switch(a.metric()) {
                    case EPIC -> {family=Family.NORMAL_DAMAGE;value=2+index*2;name="정예 소탕";}
                    case MYTHIC -> {family=Family.SPEED;value=2+index;name="신속한 지휘";}
                    case PRIMORDIAL -> {family=Family.BOSS_DAMAGE;value=6+index*2;name="거인 사냥";}
                    case TRUE_PRIMORDIAL -> {family=Family.CRITICAL;value=new int[]{10,11,12,13,14,16,18,20,22,24}[index];name="초월의 일격";}
                    case ENHANCEMENT -> {family=Family.ENHANCEMENT;value=5+index*5;name="담금질";}
                    case SESSION -> {family=Family.OPENING_ODDS;target=new Rarity[]{Rarity.NARRATIVE,Rarity.LEGENDARY,Rarity.EPIC,Rarity.MYTHIC}[index];value=4000;name="다가오는 인연 · "+target.label();}
                    case GOLD_SPENT -> {family=Family.GOLD_INCOME;value=new int[]{2,3,4,6,8}[index];name="전장의 투자";}
                    case MIRACLE -> {family=Family.ENHANCEMENT;value=new int[]{26,27,28,29,30}[index];name="기적의 담금질";}
                    case DUPLICATE -> {family=Family.DUPLICATE_ODDS;value=new int[]{10,15,20,25}[index];name="전우의 재회";}
                    default -> throw new IllegalStateException(a.id());
                }
            }
            entries.add(new Entry(a.id(),family==Family.OPENING_ODDS?name:name+" · "+value+(family==Family.START_GOLD?"골드":family==Family.FIRST_PURCHASE?"회":family==Family.ENHANCEMENT || family==Family.DUPLICATE_ODDS?"%p":"%"),family,value,role,ceiling,target,a));
        }
        ALL=List.copyOf(entries);
        var map=new LinkedHashMap<String,Entry>();for(Entry e:ALL)if(map.put(e.id(),e)!=null)throw new IllegalStateException(e.id());
        BY_ID=Map.copyOf(map);
    }
    public static Entry find(String id) { return BY_ID.get(id); }
    public static int slots(long round) { return round>=500?3:round>=250?2:round>=100?1:0; }
    private TraitCatalog() {}
}
