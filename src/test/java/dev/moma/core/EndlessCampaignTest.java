package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndlessCampaignTest {
    @Test void quickClearsStartAt500UseGameTicksResetOnFailureAndSurviveReload()throws Exception {
        var rules=CampaignRules.standard();var campaign=new Campaign(rules,true);
        var arena=new Arena("quick",UUID.randomUUID(),new Grid(6),30,100);
        var elapsed=Campaign.class.getDeclaredField("elapsed");elapsed.setAccessible(true);
        elapsed.setLong(campaign,rules.preparationTicks()+498L*rules.roundTicks()-1);
        while(campaign.completedRounds()<651) {
            campaign.beforeCombat(arena,s->UUID.randomUUID());
            int round=campaign.round(),offset=(int)((campaign.elapsed()-rules.preparationTicks())%rules.roundTicks());
            int last=campaign.wave().entries().getLast().offsetTick();
            boolean holdLast=(round==500 || round==501) && offset>=last && offset<last+100+(round==501?1:0);
            if(!holdLast){arena.enemies().forEach(e->e.damage(e.health()));arena.collectDeadEnemies();}
            campaign.afterCombat(arena);
            if(round==499)assertEquals(0,campaign.quickClearStreak());
            if(round==500 && offset==last+99)assertEquals(0,campaign.quickClearStreak());
            if(round==500 && offset==last+100)assertEquals(1,campaign.quickClearStreak());
            if(round==501 && offset>=last+100)assertEquals(0,campaign.quickClearStreak());
            if(round==550 && offset==599) {
                assertEquals(49,campaign.quickClearStreak());
                campaign=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(campaign),Campaign.class);
                arena=dev.moma.runtime.StateCodec.read(dev.moma.runtime.StateCodec.write(arena),Arena.class);
                assertEquals(49,campaign.quickClearStreak());
            }
        }
        assertEquals(150,campaign.quickClearStreak());assertFalse(arena.ended());
    }
    @Test void survivesPastFormerVictoryAndTimeoutAndGeneratesFiniteBoundedWaves() {
        var rules=new CampaignRules(6,30,100,0,100,1,1,CampaignRules.standard().healthCurve(),1,3.4,.8);
        var campaign=new Campaign(rules,true);var arena=new Arena("endless",UUID.randomUUID(),new Grid(6),30,100);
        for(int tick=0;tick<10200;tick++) {
            campaign.beforeCombat(arena,s->UUID.randomUUID());
            arena.enemies().forEach(e->e.damage(e.health()));arena.collectDeadEnemies();campaign.afterCombat(arena);
        }
        assertFalse(arena.ended());assertFalse(campaign.cleanup());assertEquals(102,campaign.completedRounds());
        assertEquals(102,campaign.round());
        for(int round:new int[]{101,110,200,10000,Integer.MAX_VALUE}) {
            Wave wave=WaveSchedule.create(round,rules);assertEquals(round,wave.round());
            assertTrue(wave.entries().size()<=39);
            assertTrue(wave.entries().stream().allMatch(e->Double.isFinite(e.enemy().health()) && e.enemy().health()>0));
        }
        double hp100=WaveSchedule.create(100,rules).entries().stream().filter(e->e.enemy().boss()).findFirst().orElseThrow().enemy().health();
        double hp110=WaveSchedule.create(110,rules).entries().stream().filter(e->e.enemy().boss()).findFirst().orElseThrow().enemy().health();
        assertEquals(Math.pow(1.1,rules.endlessHealthPower())*Math.pow((1+.11*.11)/(1+.1*.1),rules.endlessPressureBend()),hp110/hp100,1e-9);
    }
}
