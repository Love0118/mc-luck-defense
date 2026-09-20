package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndlessCampaignTest {
    @Test void survivesPastFormerVictoryAndTimeoutAndGeneratesFiniteBoundedWaves() {
        var rules=new CampaignRules(6,30,100,0,100,1,1,CampaignRules.standard().healthCurve(),1);
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
        assertEquals(Math.pow(1.1,2)*17/35/1.75,hp110/hp100,1e-9);
    }
}
