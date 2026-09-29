import html
import itertools
import json
import pathlib
import sys


ROOT = pathlib.Path(sys.argv[1])
load = lambda name: json.loads((ROOT / name).read_text(encoding="utf-8"))
stats, rarities, combat = load("stats.json"), load("rarities.json"), load("combat.json")
promotions, factors, traits, meta = (load(n) for n in ["promotions.json", "enhancement-factors.json", "traits.json", "metadata.json"])
checks = load("integration-checks.json")
units = list(dict.fromkeys(r["unit"] for r in stats))
grades = [r["rarity"] for r in rarities]
labels = {r["rarity"]: r["label"] for r in rarities}
idx = {(r["unit"], r["rarity"], r["enhancement"], r["bonus"]): r for r in stats}
ci = {(r["unit"], r["rarity"], r["enhancement"], r["scenario"]): r for r in combat}
roles = dict(zip(["MELEE_SINGLE", "MELEE_CLEAVE", "RANGED_SINGLE", "SMALL_AREA", "LARGE_AREA", "MULTI_TARGET"],
                 ["근거리 단일", "근거리 광역", "원거리 단일", "준광역", "대광역", "다중"]))
fmt = lambda n: f"{n:,.3f}".rstrip("0").rstrip(".")


def span(values):
    lo, hi = min(values), max(values)
    return fmt(lo) if abs(hi-lo) < 1e-10 else f"{fmt(lo)}~{fmt(hi)}"


def ratios(metric, bonus=0):
    return [idx[u, "COMMON", 9, bonus][metric] / idx[u, "LEGENDARY", 0, bonus][metric] for u in units]


crossovers = []
for bonus, metric in itertools.product([0, meta["maxEnhancementBonus"]], ["rawDps", "normalDps", "bossDps", "ideal10Dps"]):
    for a, b in itertools.combinations(grades, 2):
        levels = {u: next((n for n in range(20) if idx[u, a, n, bonus][metric] > idx[u, b, 0, bonus][metric] * (1+1e-12)), None) for u in units}
        crossovers.append(dict(bonus=bonus, metric=metric, lower=a, higher=b, levels=levels))
(ROOT / "crossovers.json").write_text(json.dumps(crossovers, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def cutoff(a, b, metric="rawDps", bonus=0):
    v = next(r["levels"] for r in crossovers if (r["lower"], r["higher"], r["metric"], r["bonus"]) == (a, b, metric, bonus))
    nums = [n for n in v.values() if n is not None]
    if not nums:
        return "역전 없음"
    text = f"+{min(nums)}" if min(nums) == max(nums) else f"+{min(nums)}~{max(nums)}"
    return text if len(nums) == 24 else text + f" ({len(nums)}/24종)"


economy = []
for r in rarities:
    for tier, cost in [("NORMAL", 10), ("ADVANCED", 100), ("ASCENDED", 2000), ("MIRACLE", 5000)]:
        w = r["weights"][tier]
        economy.append(dict(rarity=r["rarity"], tier=tier, cost=cost, chance=w/100000,
                            anySpeciesDraws=100000/w if w else None,
                            specificSpeciesDraws=2400000/w if w else None,
                            specificSpeciesGold=cost*2400000/w if w else None,
                            specificSpecies9Gold=10*cost*2400000/w if w else None))
(ROOT / "acquisition.json").write_text(json.dumps(economy, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

maximums = {family: max(t["value"] for t in traits if t["family"] == family) for family in {t["family"] for t in traits}}
trait_results = []
for target, n in itertools.product(["normal", "boss"], [0, 1, 5, 9, 19]):
    families = ["DAMAGE", "NORMAL_DAMAGE" if target == "normal" else "BOSS_DAMAGE", "ROLE_DAMAGE", "SPEED", "CRITICAL", "ENHANCEMENT"]
    candidates = []
    for subset in itertools.combinations(families, 4):
        damage = 1 + sum(maximums[f]/100 for f in subset if f.endswith("DAMAGE"))
        speed = 1+maximums["SPEED"]/100 if "SPEED" in subset else 1
        critical = 1+maximums["CRITICAL"]/200 if "CRITICAL" in subset else 1
        enhancement = (1+n*(1+maximums["ENHANCEMENT"]/100)+(n//5)*.5)/(1+n+(n//5)*.5) if "ENHANCEMENT" in subset else 1
        candidates.append((damage*speed*critical*enhancement, subset))
    value, selected = max(candidates)
    trait_results.append(dict(target=target, enhancement=n, multiplier=value, families=selected))
(ROOT / "four-trait-ceiling.json").write_text(json.dumps(trait_results, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

md, web = [], []


def heading(text, level=2):
    md.append("#"*level+" "+text+"\n")
    web.append(f"<h{level}>{html.escape(text)}</h{level}>")


def paragraph(text):
    md.append(text+"\n")
    web.append("<p>"+html.escape(text)+"</p>")


def table(headers, rows):
    rows = [list(map(str, r)) for r in rows]
    md.append("| " + " | ".join(headers) + " |\n|" + "|".join("---" for _ in headers) + "|\n" +
              "\n".join("| " + " | ".join(r) + " |" for r in rows) + "\n")
    web.append('<div class="table"><table><thead><tr>' + "".join("<th>"+html.escape(h)+"</th>" for h in headers) +
               "</tr></thead><tbody>" + "".join("<tr>"+"".join("<td>"+html.escape(v)+"</td>" for v in r)+"</tr>" for r in rows) + "</tbody></table></div>")


heading("등급·강화 전수 계산 — 1.0.13", 1)
paragraph("판정: ‘일반 +9가 전설 +0보다 강하다’는 주장은 같은 몹 종류끼리 비교해도 성립한다. 강화 한 단계가 기본 공격력의 100%를 더하는 구조에 비해 일반~전설의 등급 격차가 작다. 다만 사거리·보스 특수효과·감속·집중 포화와 합성 재료 수까지 보면 모든 상황에서 일반이 우월하다는 뜻은 아니다.")
paragraph(f"소스 기준: {meta['sourceCommit']}. 서버 읽기 확인 당시 실행 중인 1.0.11의 Rarity, Defender, CombatProfile, CombatEngine, UnitType, TraitCatalog, TraitLoadout, AutoPlacement, SummonTier 클래스는 분석한 공개 1.0.13 JAR와 바이트 단위로 동일했다. 라이브 JAR SHA-256: 0d1b36fe724815895b93f16827b09d4c592b61fa3964dd7986ee352e0ace0d84. 개별 진행 중 유닛의 저장 상태를 추출한 보고서는 아니다.")
paragraph(f"계산 범위: 24종 × 11등급, 승급 전 +0~19 전부, 강화 특성 없음/최대 +30%p, 총 {meta['statsRows']:,}개 실제 Defender 프로필. 미라클은 강화 상한이 없으므로 +0~30·50·100·1000을 계산하고 나머지는 같은 식으로 산출한다. 중간 강화 특성 5·10·15·20·25·26·27·28·29%p도 계수표로 전부 제공한다. 합성 이력은 무한히 다양하므로 기본 표는 하위 등급에서 물려받은 공격력이 없는 새 소환 출신을 기준으로 하고, 승급 이력은 별도 표로 구분한다.")

heading("핵심 결과")
summary = []
for metric, label in [("rawDps", "특수효과 전 기본 DPS"), ("normalDps", "상시 사거리 내 일반 적 1마리·연속 공격 최대"), ("bossDps", "상시 사거리 내 보스 1마리·연속 공격 최대"), ("ideal10Dps", "적 10마리 전부 유효 범위 내")]:
    v = ratios(metric)
    summary.append([label, f"{sum(x>1+1e-12 for x in v)}/24종", span(v)+"배"])
for scenario, label in [("single_normal", "6×6 경로 일반 적 1마리"), ("single_boss", "6×6 경로 보스 1마리"), ("crowd_48", "6×6 경로 일반 적 48마리")]:
    v = [ci[u,"COMMON",9,scenario]["dps"]/ci[u,"LEGENDARY",0,scenario]["dps"] for u in units]
    summary.append([label, f"{sum(x>1+1e-12 for x in v)}/24종", span(v)+"배"])
table(["비교 조건", "일반 +9 우위", "일반 +9 / 전설 +0"], summary)
paragraph("경로 실험에서는 보스에게 스켈레톤·스트레이·보그드·피글린 전설 +0가 앞섰다. 일반 적 1마리에서는 피글린 전설 +0가 앞섰다. 48마리 조건에서는 일반 +9가 24종 모두 앞섰으나 철골렘·가스트 등은 차이가 작다. 감속이 다른 아군에게 주는 이득, 실제 웨이브의 적 사망·과잉 피해는 이 표의 DPS에 포함하지 않는다.")

heading("1. 현재 계산식")
paragraph("기본 공격력 = 몹 원형 공격력 × 등급 공격력 배율. 근거리 단일 원형은 이미 ×2.8, 다중 원형은 ×1.8 보정되어 있다.")
paragraph("강화 공격력 = 승급에서 물려받은 공격력 + 등급 기본 공격력 × [1 + n × (1 + b) + floor(n/5) × 0.5]. n은 강화 수치, b는 강화 특성의 %p/100이다. 특성이 없으면 +1은 +100%, +9는 10.5배, +19는 21.5배다. +20은 미라클을 제외하고 즉시 승급하므로 표의 23배는 승급 직전의 이론 계수다.")
paragraph("공격 간격 = max(2, ceil(원형 간격 / 등급 공격속도 배율)). 기본 DPS = 공격력 × 20 / 공격 간격. 강화 자체는 사거리·공격속도를 높이지 않는다. 예: 토끼 전설의 간격은 ceil(10/1.4)=8틱이므로 실제 공격속도 상승은 1.25배다.")
table(["강화", "필요한 같은 등급·종 기물 수", "무특성 공격력", "강화 +30%p 공격력", "무특성 / 재료를 따로 배치한 합산 기본 DPS"],
      [[f"+{n}", n+1, fmt(1+n+(n//5)*.5)+"배", fmt(1+n*1.3+(n//5)*.5)+"배", fmt((1+n+(n//5)*.5)/(n+1))+"배"] for n in [0,1,5,9,10,15,19,20]])
paragraph("따라서 +9는 일반 1마리가 공짜로 10.5배가 된 것이 아니라 일반 10마리를 한 칸에 압축한 결과다. +9가 전설을 넘는 현상 자체는 오류의 충분조건이 아니다. 문제는 현재 등급 우선 배치·표시 방식과 실제 전투력의 관계가 맞지 않는다는 점이다.")

heading("2. 등급 기본 수치와 강화별 DPS")
table(["등급", "공격력 배율", "공속 설정 배율", "사거리 배율", "특수기 단계"],
      [[r["label"], fmt(r["damage"]), fmt(r["speed"]), fmt(r["range"]),r["ability"]] for r in rarities])
paragraph("아래는 같은 종의 일반 +0 기본 DPS를 1로 둔 배수다. 24종의 실제 정수 공격 간격을 반영한 최소~최대이며, 특수효과·경로 노출·승급 상속 공격력은 제외한다.")
table(["등급", "+0", "+1", "+5", "+9", "+19"],
      [[labels[g]] + [span([idx[u,g,n,0]["rawDps"]/idx[u,"COMMON",0,0]["rawDps"] for u in units]) for n in [0,1,5,9,19]] for g in grades])

heading("3. 상위 무강 유닛을 역전하는 최소 강화")
paragraph("같은 종, 무특성, 상속 없음. 숫자는 상위 +0보다 엄격하게 강해지는 최소 강화다. +20은 승급이므로 역전 없음은 +19까지 안 된다는 뜻이다.")
table(["하위 → 다음 등급", "기본 DPS", "일반 적 1마리", "보스 1마리", "10마리 모두 사거리 안"],
      [[labels[a]+" → "+labels[b]] + [cutoff(a,b,m) for m in ["rawDps","normalDps","bossDps","ideal10Dps"]] for a,b in zip(grades,grades[1:])])
paragraph("모든 상위 등급과의 기본 DPS 역전표:")
table(["하위 / 상위 +0"]+[labels[g] for g in grades[1:]],
      [[labels[a]]+[cutoff(a,b) if grades.index(b)>grades.index(a) else "—" for b in grades[1:]] for a in grades[:-1]])

heading("4. 일반 +9 대 전설 +0 — 24종")
paragraph("DPS는 게임 시간 1초 기준이다. ‘보스 유지’는 항상 공격 가능한 최대 연속타 조건이고 ‘이동 보스’는 아래 설명의 실제 경로 실험이다. 비율 1보다 크면 일반 +9가 강하다.")
table(["유닛", "타입", "일반 +9 기본 DPS", "전설 +0 기본 DPS", "일반 적 유지 비율", "보스 유지 비율", "이동 보스 비율", "48마리 비율"],
      [[idx[u,"COMMON",0,0]["name"], roles[idx[u,"COMMON",0,0]["role"]],fmt(idx[u,"COMMON",9,0]["rawDps"]),fmt(idx[u,"LEGENDARY",0,0]["rawDps"]),
        *[fmt(idx[u,"COMMON",9,0][m]/idx[u,"LEGENDARY",0,0][m]) for m in ["normalDps","bossDps"]],
        *[fmt(ci[u,"COMMON",9,s]["dps"]/ci[u,"LEGENDARY",0,s]["dps"]) for s in ["single_boss","crowd_48"]]] for u in units])

heading("5. 전설 이상 특수효과")
table(["타입", "적용식", "전설 / 에픽 / 신화 / 태초·진 태초 / 미라클"], [
    ["근거리 단일", "동일 대상 연속타 1~5회, 최대 1+0.24×단계", "1.24 / 1.48 / 1.72 / 1.96 / 2.20배"],
    ["근거리 광역", "120도 부채꼴, 명중 시 40틱 감속", "18 / 26 / 34 / 42 / 50% 감속"],
    ["원거리 단일", "보스에게 1+0.5×단계", "1.5 / 2 / 2.5 / 3 / 3.5배"],
    ["준광역", "주 대상만 1+0.3×단계", "1.3 / 1.6 / 1.9 / 2.2 / 2.5배"],
    ["대광역", "원형 반경에 0.5×단계 블록 추가", "+0.5 / 1 / 1.5 / 2 / 2.5블록"],
    ["다중", "기본 3명(벡스 4명)+단계", "4~5 / 5~6 / 6~7 / 7~8 / 8~9명"],
])
paragraph("광역·준광역·부채꼴은 적 마릿수 상한 없이 실제 범위 안의 적에게 피해를 준다. 다중은 지정된 수까지만 서로 다른 적을 공격한다. 단일 대상 DPS로 광역의 실전 가치를 단정하면 안 된다.")

heading("6. 더 큰 문제: 같은 등급·강화인데 공격력이 다르다")
paragraph("승급은 기존 피해를 잃지 않도록 +20 공격력을 물려준다. 새 등급의 기본 공격력보다 큰 부분은 inheritedDamage로 남는다. 아래는 상속이 없던 하위 기물 하나를 +20으로 만든 경우이며, 추가 합성 이력까지 같다는 뜻은 아니다.")
table(["승급", "승급 +0 / 직접 뽑은 같은 등급 +0", "강화 +30%p일 때", "+19 → 승급 +0 기본 DPS 증가"],
      [[labels[a]+" → "+labels[b],
        fmt(next(r["vsFresh0"] for r in promotions if (r["unit"],r["from"],r["bonus"])==("WOLF",a,0)))+"배",
        fmt(next(r["vsFresh0"] for r in promotions if (r["unit"],r["from"],r["bonus"])==("WOLF",a,30)))+"배",
        span([r["dpsStep"] for r in promotions if r["from"]==a and r["bonus"]==0])+"배"] for a,b in zip(grades,grades[1:])])
paragraph(f"실제 Arena.summon 재현: 일반 늑대 21마리와 레어 늑대 1마리를 합성한 레어 +1의 공격력은 {fmt(checks['twentyOneCommonPlusOneRare']['damage'])}. 레어 늑대 2마리를 합성한 레어 +1은 {fmt(checks['twoFreshRare']['damage'])}. 표시가 같은데 {fmt(checks['sameLabelDamageRatio'])}배 차이다. 서사 +20으로 나온 전설 +0도 직접 뽑은 전설 +0보다 20.125배 강하다. 상세 GUI의 공격력은 실제 profile을 표시하지만 등급·강화 이름만으로 전투력을 판단할 수 없다.")
paragraph("자동 배치 재현: 일반 늑대 +9와 전설 늑대 +0 36마리를 보유하면 36칸은 모두 전설에 배정되고 일반 +9는 제외된다. 실제 기본 DPS나 상속 공격력이 등급 우선순위를 뒤집지는 못한다. 이는 현재의 ‘높은 등급 먼저’ 정책이 강화·상속 수치와 충돌한다는 증거다.")

heading("7. 강화 특성과 나머지 전투 특성")
table(["강화 추가 %p", "+1", "+5", "+9", "+10", "+15", "+19", "+20 승급 직전"],
      [[str(b)] + [fmt(1+n*(1+b/100)+(n//5)*.5)+"배" for n in [1,5,9,10,15,19,20]] for b in sorted({r['bonus'] for r in factors})])
paragraph(f"최대 강화 특성 +30%p에서는 일반 +9의 강화 계수가 10.5에서 13.2로 높아진다. 전설 +0는 이 특성의 직접 이득이 없으므로 기본 DPS 격차가 {span(ratios('rawDps',30))}배로 벌어진다. 강화 특성은 일반~미라클에 모두 적용된다.")
paragraph("같은 타입·같은 목표를 때리는 두 유닛에 동일한 피해·공속·치명타 특성을 적용하면 장기 평균 비교 배율에서는 서로 상쇄된다. 피해 특성끼리는 합연산, 공속은 별도 배율, 치명타 기대 배율은 1+확률/200이다. 강화 특성은 강화된 쪽에 더 유리하다. 획득·중복 보정은 공격력보다 기물을 모으는 속도를 바꾸므로 별도 확률 실험이 필요하다.")
family_ko = {"DAMAGE":"전체 피해","NORMAL_DAMAGE":"일반 적 피해","BOSS_DAMAGE":"보스 피해","ROLE_DAMAGE":"타입 피해","SPEED":"공속","CRITICAL":"치명타","ENHANCEMENT":"강화"}
paragraph("참고: 같은 타입에 맞춘 최고 단계 특성 4칸의 이론상 직접 전투력 최대치. 무특성의 같은 등급·강화를 1로 둔다. 모든 최고 업적을 이미 달성했다고 가정하며, 골드·소환 확률·해금 가능성과 실제 최고 라운드 조합을 뜻하지 않는다.")
table(["목표", "강화", "최대 평균 DPS 배율", "4개 특성 계열"],
      [["일반 적" if r['target']=='normal' else '보스', f"+{r['enhancement']}",fmt(r['multiplier'])+"배",' · '.join(family_ko[f] for f in r['families'])] for r in trait_results])

heading("8. 획득 비용도 구분해야 한다")
paragraph("아래는 10골드 뽑기의 고정 기본 확률에서 특정 종·등급을 모으는 평균이다. 첫 3회 보정, 패시브, 중복 특성, 판매 환급, 하위 승급 합류는 제외한 총지출이다. 일반 +9와 특정 전설 1마리의 비교에 ‘아무 전설’ 확률을 섞으면 비용 비교가 틀어진다.")
table(["등급", "등급 확률", "특정 종 +0 기대 뽑기 수", "특정 종 +9 기대 총골드"],
      [[labels[r['rarity']],fmt(r['chance']*100)+"%",fmt(r['specificSpeciesDraws']),fmt(r['specificSpecies9Gold'])] for r in economy if r['tier']=='NORMAL' and r['chance']])
paragraph("예: 특정 종 일반 +9는 평균 약 480회·4,800골드, 같은 종 전설 +0는 4,800회·48,000골드다. 반면 종을 가리지 않은 전설 1마리는 평균 200회·2,000골드다. 한 판의 실제 도달 시점은 다른 종의 동시 합성, 판매, 초반 보정 때문에 이 단일 목표 평균과 다르다. 100/2,000/5,000골드 뽑기까지의 44개 획득 기대값은 acquisition.json에 포함했다.")

heading("9. 타입별 전투 기여도도 균등하지 않다")
paragraph("같은 등급 +0, 각 타입의 4종 평균을 비교했다. 각 행에서 가장 높은 타입을 100으로 두며 아래 두 표의 분모는 서로 다르다. 좁은 사거리의 기물도 자신에게 가장 유리한 빈 칸을 사용하고, 적이 죽지 않으므로 과잉 피해가 없다. 실제 36칸의 자리 경쟁이나 처치 속도를 측정한 순위는 아니다.")
role_results = []
for scenario, title in [("single_boss", "경로 보스 1마리: 타입별 평균 DPS 상대값"), ("crowd_48", "경로 일반 적 48마리: 타입별 평균 DPS 상대값")]:
    paragraph(title)
    rows = []
    for g in grades:
        averages = {role: sum(ci[u,g,0,scenario]['dps'] for u in units if idx[u,g,0,0]['role']==role)/4 for role in roles}
        best = max(averages.values())
        rows.append([labels[g]]+[fmt(averages[role]/best*100) for role in roles])
        for role in roles:
            role_results.append(dict(rarity=g,scenario=scenario,role=role,averageDps=averages[role],relativeToBest=averages[role]/best))
    table(["등급"]+list(roles.values()), rows)
(ROOT / "role-comparisons.json").write_text(json.dumps(role_results,ensure_ascii=False,separators=(",", ":")),encoding="utf-8")
paragraph("원거리 단일이 보스에 강하고 다수전에서 낮은 것은 역할 차이로 설명할 수 있다. 반면 준광역은 이 실험의 보스와 다수전 양쪽에서 낮은 편이다. 전설 준광역 평균은 보스 최상위 타입의 43.1%, 48마리 최상위 타입의 42.4%다. 준광역의 실제 웨이브별 처치 효율을 우선 확인할 근거가 된다. 적이 한 지점에 몰리는 상황에서는 원형 광역의 상대 가치가 달라진다.")

heading("10. 검증 방법과 한계")
paragraph(f"실제 공개 배포 JAR를 참조하여 Defender.merge, Arena.summon, AutoPlacement.arrange, CombatEngine.tick을 실행했다. {meta['statsRows']:,}개 프로필을 공식과 대조하고, {meta['specialEffectChecks']}개 특수효과·타깃 수 계산을 전투 엔진에 직접 대조했다. 경로 전투는 {meta['engineTrials']}개 조건이며 일반 +9의 선형 배율은 72개 독립 재실행으로 확인했다.")
paragraph("경로 실험: 6×6 필드, 바깥 84블록 경로, 이동속도 초당 2블록, 사거리로 경로를 가장 많이 덮는 고정 칸을 각 유닛에 따로 선택. 42초 예열 후 336초 측정. 일반 적 1마리, 보스 1마리, 경로에 처음부터 고르게 놓인 일반 적 48마리를 각각 사용했다. 체력은 측정 동안 죽지 않도록 1e20으로 고정해 과잉 피해를 제거했다. 공격·타깃 변경·범위·감속은 실제 엔진 판정이다.")
paragraph("이는 라운드 도달률 몬테카를로가 아니다. 적 밀도·다른 배치·보스 체력·과잉 피해·48마리보다 많은 적·다른 아군의 감속 이득·수동 재배치에 따라 결과가 달라진다. 최고 등급으로 진행 중인 기존 유닛은 이전 버전에서 저장된 profile과 합성 이력도 영향을 줄 수 있다. 현재 서버의 개별 유닛 피해를 이 표만으로 역산할 수는 없다.")

heading("11. 밸런스 판단")
paragraph("현재 상태를 ‘등급만 보고 상위 유닛이 더 좋다고 판단해도 되는 밸런스’라고 보기는 어렵다. 일반~서사는 +1만으로 바로 다음 등급 +0의 기본 DPS를 넘고, 일반 +5부터 모든 종에서 전설 +0의 기본 DPS를 넘는다. 반대로 신화→태초는 40배, 태초→진 태초와 진 태초→미라클은 공격력 100배 도약이 있다. 강화 계수 하나로 낮은 등급의 촘촘한 간격과 상위의 큰 간격을 동시에 다루고 있다.")
paragraph("우선 손볼 항목은 세 가지다. ① 등급·강화·획득 비용의 목표 전투력을 하나의 표로 다시 정하기 ② 승급 상속 공격력을 표시와 배치 판단에 반영하거나 승급 규칙을 재설계하기 ③ 강화된 하위 등급을 실제 전투 기여도와 무관하게 밀어내는 자동 배치 정책을 함께 조정하기.")
paragraph("단순히 강화 증가량을 +100%에서 +10%로 바꾸면 +9가 10.5배에서 2.4배로 줄어 재료 10마리의 기본 DPS 중 76%를 잃는다. 초반 난이도를 다시 검증해야 한다. 반대로 ‘모든 종의 전설 +0가 일반 +9 기본 DPS보다 높아야 한다’만 만족시키려면 현재 다른 수치를 유지할 때 전설 공격력 배율이 8에서 14.7 초과가 필요하다. 최대 강화 특성까지 포함하면 18.48 초과가 필요하다. 이는 조건을 만족하는 수학적 경계이지 추천 패치 수치가 아니다.")
paragraph("이번 작업에서는 계산기와 검증 결과만 추가했다. 서버 수치·등급·강화·배치 정책을 변경하지 않았다. 실제 조정 후에는 같은 전수표와 초반·후반 도달률 시뮬레이션을 함께 다시 검증해야 한다.")

heading("자료와 재현")
paragraph("stats.json: 11,232개 프로필 / combat.json: 864개 전투 실험 / crossovers.json: 440개 등급 쌍·특성·피해 기준별 역전 결과(각 24종) / promotions.json: 480개 승급 / enhancement-factors.json: 중간 특성 전체 계수 / acquisition.json: 뽑기 4단계 기대값 / four-trait-ceiling.json: 최고 특성 4칸의 직접 전투력 상한 / integration-checks.json: 실제 합성·배치 재현.")
paragraph("근거 소스: src/main/java/dev/moma/core/{Rarity,CombatProfile,Defender,CombatEngine,AttackGeometry,UnitType,TraitLoadout,AutoPlacement,Arena,SummonTier}.java. 기존 RosterTest의 상위 등급 격차 검증은 +0끼리, ProgressionTest는 승급 시 공격력 비감소를 검사한다. 이 두 조건만으로 등급과 강화가 섞인 상대 가치는 검증되지 않는다.")
md.append("재현 명령(PowerShell, 저장소 루트):\n\n```powershell\njavac -encoding UTF-8 -cp '.runtime/release-1.0.13/MCLuckDefense.jar' -d target/enhancement-audit benchmarks/balance/EnhancementAudit.java\njava -Xmx2G -cp 'target/enhancement-audit;.runtime/release-1.0.13/MCLuckDefense.jar' dev.moma.core.EnhancementAudit docs/balance/enhancement-audit-1.0.13 d9e1790e700d5c667103e500ae7629a46ffe844d\npython -X utf8 benchmarks/balance/report_enhancement_audit.py docs/balance/enhancement-audit-1.0.13\n```\n")
(ROOT / "report.md").write_text("\n".join(md), encoding="utf-8")

options = ''.join(f'<option value="{u}">{html.escape(idx[u,"COMMON",0,0]["name"])}</option>' for u in units)
explorer = '<section class="explorer"><h2>유닛별 전체 수치 조회</h2><p>직접 뽑은 기물 기준 · 승급 상속 없음 · DPS는 게임 시간 초당</p><label>유닛 <select id="unit">'+options+'</select></label> <label>강화 특성 <select id="bonus"><option value="0">없음</option><option value="30">+30%p</option></select></label> <label>항목 <select id="metric"><option value="rawDps">기본 DPS</option><option value="normalDps">일반 적 1마리 지속 DPS</option><option value="bossDps">보스 지속 DPS</option><option value="ideal10Dps">10마리 이상적 합산 DPS</option><option value="damage">1회 기본 공격력</option><option value="interval">공격 간격(틱)</option><option value="range">사거리(블록)</option><option value="radius">원형 광역 반경(블록)</option></select></label><div class="table" id="matrix"></div><p>미라클 +20 이후는 아래 별도 행으로 표시합니다. 부채꼴 범위는 원형 반경이 아닌 사거리로 판정합니다.</p></section>'
payload = json.dumps(stats, ensure_ascii=False, separators=(",", ":"))
script = "const rows="+payload+";const grades="+json.dumps(grades)+";const labels="+json.dumps(labels,ensure_ascii=False)+";"+"""
function render(){const unit=document.getElementById('unit').value,bonus=Number(document.getElementById('bonus').value),metric=document.getElementById('metric').value;
const selected=rows.filter(r=>r.unit===unit&&r.bonus===bonus);let out='<table><thead><tr><th>등급</th>';
for(let n=0;n<20;n++)out+='<th>+'+n+'</th>';out+='</tr></thead><tbody>';
for(const grade of grades){out+='<tr><th>'+labels[grade]+'</th>';for(let n=0;n<20;n++){const r=selected.find(r=>r.rarity===grade&&r.enhancement===n);out+='<td>'+r[metric].toLocaleString('ko-KR',{maximumFractionDigits:3})+'</td>';}out+='</tr>';}
out+='</tbody></table><table><thead><tr><th>미라클 추가 강화</th><th>값</th></tr></thead><tbody>';
for(const r of selected.filter(r=>r.rarity==='MIRACLE'&&r.enhancement>=20))out+='<tr><td>+'+r.enhancement+'</td><td>'+r[metric].toLocaleString('ko-KR',{maximumFractionDigits:3})+'</td></tr>';
document.getElementById('matrix').innerHTML=out+'</tbody></table>';}
for(const id of ['unit','bonus','metric'])document.getElementById(id).addEventListener('change',render);render();
"""
document = '<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>등급·강화 밸런스 전수 계산</title><style>body{margin:0;background:#10151d;color:#e5edf7;font:16px/1.65 system-ui,sans-serif}main{max-width:1450px;margin:auto;padding:32px}h1{font-size:32px}h2{margin-top:42px;color:#8bc9ff}p{max-width:1100px;color:#cbd5e1}.table{overflow:auto;margin:18px 0}table{border-collapse:collapse;white-space:nowrap;width:100%;font-variant-numeric:tabular-nums}th,td{padding:9px 12px;text-align:right;border:1px solid #334155}th:first-child,td:first-child{text-align:left}th{background:#1e293b}tr:nth-child(even){background:#17202b}select{font:inherit;background:#1e293b;color:#fff;border:1px solid #64748b;padding:6px;margin:8px}.explorer{border:1px solid #425675;border-radius:12px;padding:20px;background:#121e2e}a{color:#8bc9ff}</style><main>'+''.join(web[:4])+explorer+''.join(web[4:])+'<p><a href="report.md">Markdown 보고서</a> · <a href="stats.json">전체 프로필 데이터</a> · <a href="combat.json">실제 전투 결과</a></p></main><script>'+script+'</script></html>'
(ROOT / "report.html").write_text(document, encoding="utf-8")
print(f"Created report.md and report.html; {len(crossovers)} crossover comparisons; {len(economy)} acquisition rows.")
