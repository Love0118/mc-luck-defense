package dev.moma.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndlessPressureTest {
    private double regularHealth(Wave wave) {
        return wave.entries().stream().filter(e->!e.enemy().boss()).mapToDouble(e->e.enemy().health()).sum();
    }
    @Test void ordinaryPressureAndExactGoldBudgetDoNotResetWithThemesOrWardens() {
        var rules=CampaignRules.standard().withEndlessPressureBend(0);
        double initial=regularHealth(WaveSchedule.create(100,rules)),previous=initial;
        for(int round=101;round<=10000;round++) {
            Wave wave=WaveSchedule.create(round,rules);
            double health=regularHealth(wave);
            assertTrue(health>previous,"Pressure dropped at round "+round);
            assertEquals(initial*Math.pow(round/100.0,rules.endlessHealthPower()),health,health*1e-12);
            long gold=wave.entries().stream().filter(e->!e.enemy().boss()).mapToLong(e->Gold.units(e.enemy().reward())).sum();
            assertEquals(Gold.units(WaveSchedule.reward(round)*31),gold,"Reward drift at round "+round);
            previous=health;
        }
    }
    @Test void laterBossesGrowAndBossTuningDoesNotAlterOrdinaryPressureOrRewards() {
        var rules=CampaignRules.standard();double previous=0;
        for(int round=100;round<=10000;round+=10) {
            Wave wave=WaveSchedule.create(round,rules),half=WaveSchedule.create(round,rules.withBossHealthScale(rules.bossHealthScale()/2));
            var boss=wave.entries().stream().filter(e->e.enemy().boss()).findFirst().orElseThrow().enemy();
            assertTrue(boss.health()>previous);previous=boss.health();
            for(int i=0;i<wave.entries().size();i++) {
                var before=wave.entries().get(i).enemy();var after=half.entries().get(i).enemy();
                assertEquals(before.health()*(before.boss()?.5:1),after.health(),before.health()*1e-12);
                assertEquals(before.reward(),after.reward());
            }
        }
    }
    @Test void powerOutpacesQuadraticMaterialGrowthAndOtherRuleEditsRetainIt() {
        var rules=CampaignRules.standard().withEndlessHealthPower(3.6);
        assertEquals(3.6,rules.withHealthScale(2).withBossHealthScale(.2).withHealthCurve(rules.healthCurve()).endlessHealthPower());
        assertEquals(rules.endlessPressureBend(),rules.withHealthScale(2).withBossHealthScale(.2).withHealthCurve(rules.healthCurve()).endlessPressureBend());
        for(double invalid:new double[]{0,2,Double.NaN,Double.POSITIVE_INFINITY,9})
            assertThrows(IllegalArgumentException.class,()->rules.withEndlessHealthPower(invalid));
        assertThrows(IllegalArgumentException.class,()->WaveSchedule.create(0,rules));
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,5})
            assertThrows(IllegalArgumentException.class,()->rules.withEndlessPressureBend(invalid));
    }
    @Test void latePressureAcceleratesSmoothlyAcrossRepeatingCycleBoundaries() {
        var rules=CampaignRules.standard();
        double h500=regularHealth(WaveSchedule.create(500,rules));
        double h1000=regularHealth(WaveSchedule.create(1000,rules));
        double h2000=regularHealth(WaveSchedule.create(2000,rules));
        assertTrue(h2000/h1000>h1000/h500);
        for(int boundary:new int[]{500,1000,1500,2000,2500,10000}) {
            double before=regularHealth(WaveSchedule.create(boundary,rules));
            double after=regularHealth(WaveSchedule.create(boundary+1,rules));
            assertTrue(after>before);assertTrue(after/before<1.02);
        }
    }
}
