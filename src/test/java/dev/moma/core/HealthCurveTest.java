package dev.moma.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthCurveTest {
    @Test void standardCurveSpansTheSimulationAndHealthDoesNotRepeatWithTheField() {
        var rules=CampaignRules.standard();
        assertEquals(1000,rules.healthCurve().anchors().getLast().round());
        for(int round=101;round<=10000;round+=100) {
            var previous=WaveSchedule.create(round-100,rules).entries();
            var current=WaveSchedule.create(round,rules).entries();
            assertEquals(previous.size(),current.size());
            for(int i=0;i<current.size();i++) {
                assertTrue(Double.isFinite(current.get(i).enemy().health()));
                assertTrue(current.get(i).enemy().health()>previous.get(i).enemy().health());
                assertEquals(previous.get(i).enemy().type(),current.get(i).enemy().type());
            }
        }
    }
    @Test void newEarlyCurvePreservesEveryLateWaveFromRoundOneThousand() {
        var rules=CampaignRules.standard();
        var original=rules.withHealthCurve(HealthCurve.parse("1:60,10:150,20:900,26:1120,30:1220,40:2050,50:3200,60:6000,70:9000,80:15000,90:26000,100:48000"));
        for(int round=1000;round<=10000;round++) {
            var before=WaveSchedule.create(round,original).entries();
            var after=WaveSchedule.create(round,rules).entries();
            assertEquals(before.size(),after.size());
            for(int i=0;i<before.size();i++) {
                var a=before.get(i).enemy();var b=after.get(i).enemy();
                assertEquals(a.health(),b.health(),a.health()*1e-12);
                assertEquals(a.reward(),b.reward());assertEquals(a.speed(),b.speed());
            }
        }
    }
    @Test void laterReliefPreservesEarlyWavesAndEveryRewardSpeedAndSpawn() {
        var current=CampaignRules.standard();
        var old=current.withHealthCurve(HealthCurve.parse("1:0.0000003,10:0.000005,20:0.00008,26:0.00018,30:0.00025,40:0.0007,50:0.004,60:0.04,70:0.6,80:6,90:250,100:4000,150:12000,200:200000,300:3500000,400:8500000,500:16000000,600:29215470.930176035,700:46419190.23836803,800:68183714.92227086,900:102364663.51858358,1000:146879926.25949115"));
        for(int round=1;round<=10000;round++) {
            var before=WaveSchedule.create(round,old).entries();
            var after=WaveSchedule.create(round,current).entries();
            assertEquals(before.size(),after.size());
            for(int i=0;i<before.size();i++) {
                var a=before.get(i);var b=after.get(i);
                assertEquals(a.offsetTick(),b.offsetTick());
                assertEquals(a.enemy().type(),b.enemy().type());assertEquals(a.enemy().boss(),b.enemy().boss());
                assertEquals(a.enemy().reward(),b.enemy().reward());assertEquals(a.enemy().speed(),b.enemy().speed());
                if(round<=200 || round>=1000)assertEquals(a.enemy().health(),b.enemy().health());
                else assertTrue(b.enemy().health()<a.enemy().health(),"No relief at round "+round);
            }
        }
    }
    @org.junit.jupiter.api.Test void endlessAnchorsInterpolateAndRejectOutOfRange() {
        var curve=HealthCurve.parse("1:60,100:250000,300:1500000,500:10000000,1000:25000000,2000:80000000");
        assertEquals(80000000,curve.at(2000));
        assertEquals(Math.sqrt(1500000d*10000000),curve.at(400),1e-6);
        assertThrows(IllegalArgumentException.class,()->curve.at(2001));
        var rules=CampaignRules.standard().withHealthCurve(curve);
        assertTrue(WaveSchedule.create(2100,rules).entries().stream().allMatch(e->Double.isFinite(e.enemy().health())));
    }
    @Test void anchorsAreExactAndInterpolationIsMonotonic() {
        HealthCurve curve = HealthCurve.parse("1:100,30:1000,50:2000,70:10000,90:20000,100:20000");
        for (var anchor : curve.anchors()) assertEquals(anchor.health(), curve.at(anchor.round()), 1e-9);
        for (int round = 2; round <= 100; round++) assertTrue(curve.at(round) >= curve.at(round - 1));
        assertEquals(curve, HealthCurve.parse(curve.specification()));
        assertEquals(Math.sqrt(1000 * 2000), curve.at(40), 1e-9);
    }
    @Test void rejectsMissingEndpointsNonFiniteDecreasingOrDuplicateAnchors() {
        for (String invalid : new String[]{"30:100,100:200", "1:100,90:200", "1:100,100:NaN", "1:100,50:90,100:200", "1:100,1:110,100:200", "1:0,100:200"})
            assertThrows(IllegalArgumentException.class, () -> HealthCurve.parse(invalid));
        assertThrows(IllegalArgumentException.class, () -> HealthCurve.parse("1:10,100:100").at(101));
    }
}
