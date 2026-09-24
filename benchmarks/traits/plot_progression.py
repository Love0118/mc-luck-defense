"""Plot validated cohort data; zero observations remain explicit in the source table."""
import argparse
import csv
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
from matplotlib import pyplot as plt, font_manager
from summarize_progression import NAMES

p=argparse.ArgumentParser()
p.add_argument('analysis',type=Path)
p.add_argument('output',type=Path)
a=p.parse_args()
font=Path('C:/Windows/Fonts/malgun.ttf')
if font.exists():
    font_manager.fontManager.addfont(str(font))
    plt.rcParams['font.family']='Malgun Gothic'
plt.rcParams['axes.unicode_minus']=False
with (a.analysis/'reach.csv').open(encoding='utf-8',newline='') as f:
    rows=list(csv.DictReader(f))
cases=list(dict.fromkeys(x['scenario'] for x in rows))
colors=['#64748b','#16a085','#8b5cf6','#ed8c26','#e05b83','#2563eb']
fig,axes=plt.subplots(1,2,figsize=(13,5.7))
for name,color in zip(cases,colors):
    data=[x for x in rows if x['scenario']==name]
    early=[x for x in data if int(x['round'])<=1000]
    late=[x for x in data if int(x['round'])>=1000 and int(x['reached'])>0]
    axes[0].plot([int(x['round']) for x in early],[float(x['percent']) for x in early],color=color,marker='o',markersize=3,label=NAMES[name])
    axes[1].plot([int(x['round']) for x in late],[float(x['percent']) for x in late],color=color,marker='o',markersize=4,label=NAMES[name])
    if late:
        axes[1].fill_between([int(x['round']) for x in late],[float(x['wilson95_low_percent']) for x in late],
                             [float(x['wilson95_high_percent']) for x in late],color=color,alpha=.12)
axes[0].set(title='초반·중반 도달률',xlabel='라운드',ylabel='도달률 (%)',ylim=(0,101),xlim=(0,1000))
axes[1].set(title='후반 도달률 · 음영은 95% 구간',xlabel='라운드',ylabel='도달률 (%, 로그 눈금)',xlim=(900,max(int(x['round']) for x in rows)*1.025),yscale='log')
for ax in axes:
    ax.grid(alpha=.18)
    ax.spines[['top','right']].set_visible(False)
axes[0].legend(frameon=False,fontsize=9)
fig.suptitle('특성 구성에 따른 라운드 도달 곡선',fontsize=17)
fig.text(.07,.02,'라운드 진입 기준 · 후반 그래프의 0회 관측 점은 생략 · 정확한 판 수와 0회 관측은 도달률 표 참고',fontsize=9,color='#475569')
fig.tight_layout(rect=(0,.055,1,.95))
a.output.mkdir(parents=True,exist_ok=True)
fig.savefig(a.output/'reach-curve.png',dpi=180)
fig.savefig(a.output/'reach-curve.svg')
